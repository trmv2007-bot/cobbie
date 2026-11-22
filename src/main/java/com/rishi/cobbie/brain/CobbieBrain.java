package com.rishi.cobbie.brain;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.CobbieMemory;
import com.rishi.cobbie.skill.ActionStep;
import com.rishi.cobbie.skill.SkillExecutor;
import com.rishi.cobbie.skill.SkillRegistry;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskStep;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The wire between chat, the model and Cobbie's body.
 *
 * <p>Rules that matter, all enforced here:
 * <ul>
 *   <li><b>Activation is explicit.</b> He answers when the whole word "cobbie" appears (or when the
 *       owner turns on always-listen). Unrelated multiplayer conversation is never sent to an AI
 *       provider.</li>
 *   <li><b>Ownership.</b> A player may only talk to their own companion, or one nobody owns.
 *       Someone else's Cobbie is invisible to them.</li>
 *   <li><b>Threading.</b> The world is read on the server thread before going async; every reply is
 *       scheduled back through {@link MinecraftServer#execute}. Nothing here touches blocks,
 *       entities or chat from a worker thread.</li>
 *   <li><b>Failure is not a conversation.</b> A failed request sends a friendly line to the asker
 *       only and stores nothing — no half-turns, no fabricated answers in tomorrow's context.</li>
 *   <li><b>Results come back.</b> When a plan settles, the verified report is shown to the owner,
 *       stored in memory, credited to the skill that ran it, and fed to the model, so he can only
 *       ever talk about what actually happened.</li>
 * </ul>
 */
public final class CobbieBrain {

	/** "cobbie" as a whole word — a player called Cobbieman or a URL must not summon him. */
	private static final Pattern TRIGGER = Pattern.compile("(?i)(^|[^a-z0-9])cobbie([^a-z0-9]|$)");

	private static volatile LlmClient client;
	private static volatile ConnectionState lastState = ConnectionState.DISCONNECTED;
	private static volatile String lastStateDetail = "no request made yet";

	private static final AtomicInteger inFlight = new AtomicInteger();
	private static final ConcurrentHashMap<UUID, Long> lastChatAt = new ConcurrentHashMap<>();
	/** dedup key -> cancelled flag. putIfAbsent makes check-and-register atomic. */
	private static final ConcurrentHashMap<String, AtomicBoolean> pending = new ConcurrentHashMap<>();
	/** Completion reports waiting for the next server tick: {CobbieEntity, report}. */
	private static final ArrayDeque<Object[]> reportQueue = new ArrayDeque<>();

	private CobbieBrain() {}

	// ==================================================================
	// Wiring
	// ==================================================================

	/** Called from {@link CobbieConfig} once settings are known. Performs no network I/O. */
	public static void configure(String baseUrl, String model, String apiKey, String provider,
			double temperature, int maxTokens, int connectTimeoutSeconds, int readTimeoutSeconds,
			int maxRetries) {
		client = new LlmClient(baseUrl, model, apiKey, provider, temperature, maxTokens,
				connectTimeoutSeconds, readTimeoutSeconds, maxRetries);
		lastState = ConnectionState.DISCONNECTED;
		lastStateDetail = "configured, not probed yet";
		Cobbie.LOGGER.info("Cobbie brain configured: {} model={}", client.endpoint(), model);
	}

	public static LlmClient client() {
		return client;
	}

	public static ConnectionState lastState() {
		return lastState;
	}

	public static String lastStateDetail() {
		return lastStateDetail;
	}

	public static int inFlightCount() {
		return inFlight.get();
	}

	/** Records a probe so {@code /cobbie status} reports reachability and model presence separately. */
	public static void noteProbe(OllamaDiscovery.Probe probe) {
		if (probe == null) return;
		lastState = probe.state();
		lastStateDetail = probe.describe();
	}

	public static void noteRequestFailure(String error) {
		lastState = ConnectionState.REQUEST_FAILED;
		lastStateDetail = error == null ? "unknown" : error;
	}

	/** Hook up chat and the tick pump. Called once from {@code Cobbie.onInitialize}. */
	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> handleChat(message, sender));
		ServerTickEvents.END_SERVER_TICK.register(CobbieBrain::onServerTick);
		Cobbie.LOGGER.info("Cobbie brain listening (activation mode {})", CobbieConfig.getChatMode());
	}

	/** Chat entry point. Always invoked on the server thread. */
	public static void handleChat(PlayerChatMessage message, ServerPlayer sender) {
		String text = message == null ? null : message.signedContent();
		if (text == null || text.isBlank() || sender == null) return;
		onChat(sender, text);
	}

	/** Per-tick pump: delivers plan reports on the server thread. */
	public static void onServerTick(MinecraftServer server) {
		deliverReports(server);
		com.rishi.cobbie.entity.CobbieEntity.tickDeferredDrops();
	}

	/** Owner asked him to stop thinking about the last thing they said. */
	public static int cancelRequestsFor(UUID speaker) {
		int n = 0;
		for (String key : pending.keySet()) {
			if (key.startsWith(speaker + ":")) {
				pending.get(key).set(true);
				n++;
			}
		}
		return n;
	}

	/** Forget a companion who went away, so nothing keeps a reference to him. */
	public static void forget(CobbieEntity cobbie) {
		if (cobbie != null) cobbie.cancelTask("i had to stop");
	}

	// ==================================================================
	// Chat in
	// ==================================================================

	private static void onChat(ServerPlayer sender, String text) {
		final String name = sender.getGameProfile().name();
		final UUID speakerId = sender.getUUID();
		final boolean mentioned = TRIGGER.matcher(text).find();

		// 1. Slash-free commands the owner gives directly (never sent to the model).
		if (isDirectCommand(text)) {
			handleDirectCommand(sender, text);
			return;
		}

		// 2. Is he being spoken to at all?
		if (!addressedTo(sender, mentioned)) return;

		// 3. Which companion may this player talk to?
		final CobbieEntity body = companionOf(sender);
		if (body == null) {
			if (mentioned) sender.sendSystemMessage(cobbieSays("spawn me with /spawncobbie first"));
			return;
		}
		if (client == null) {
			sender.sendSystemMessage(cobbieSays("my brain is not configured - check config/cobbie.json"));
			return;
		}

		// 3b. Short imperatives run deterministically: the model narrated "waving hello" without
		// queueing the wave, and "coming right over" without moving. A command a player typed is not
		// a conversation to be interpreted.
		Imperatives.Hit hit = Imperatives.match(text);
		if (hit != null) {
			runImperative(sender, body, hit);
			return;
		}

		// 4. Per-player throttle.
		long now = System.currentTimeMillis();
		long cooldown = Math.max(200, CobbieConfig.getChatCooldownMs());
		Long last = lastChatAt.get(speakerId);
		if (last != null && now - last < cooldown) {
			if (mentioned) sender.sendSystemMessage(cobbieSays("hold on, still thinking about the last thing"));
			return;
		}

		// 5. Concurrency cap and duplicate collapse.
		if (inFlight.get() >= CobbieConfig.getMaxConcurrentRequests()) {
			sender.sendSystemMessage(cobbieSays("busy right now, give me a second"));
			return;
		}
		final String key = speakerId + ":" + text.hashCode();
		if (pending.putIfAbsent(key, new AtomicBoolean(false)) != null) {
			sender.sendSystemMessage(cobbieSays("I already heard that one"));
			return;
		}
		lastChatAt.put(speakerId, now);
		inFlight.incrementAndGet();

		// 6. Perceive the world HERE (server thread) and assemble the prompt.
		final WorldSnapshotHolder snapshot = new WorldSnapshotHolder(body.perceive());
		final MinecraftServer server = sender.level() instanceof ServerLevel sl ? sl.getServer() : null;
		final List<LlmClient.Msg> turns = conversationTurns(speakerId);
		final String system = CobbiePrompt.systemPrompt(snapshot.value,
				CobbieConfig.isMemoryEnabled() ? CobbieMemory.longTermSummary(speakerId) : "",
				CobbieConfig.isMemoryEnabled() ? CobbieMemory.currentObjective(speakerId) : "",
				SkillRegistry.describeForPrompt(4));
		final String user = CobbiePrompt.userMessage(name, text);

		client.complete(system, turns, user).thenAccept(result -> {
			inFlight.decrementAndGet();
			AtomicBoolean cancelled = pending.remove(key);
			if (server == null) return;

			if (cancelled != null && cancelled.get()) {
				Cobbie.LOGGER.info("Cobbie discarded a stale reply for {} (owner cancelled)", name);
				return;
			}
			if (result == null || !result.success()) {
				String why = result == null ? "no answer" : result.error();
				noteRequestFailure(why);
				Cobbie.LOGGER.warn("Cobbie LLM request failed for {}: {}", name, why);
				// Nothing is stored: a failed request is not a conversation turn.
				server.execute(() -> sender.sendSystemMessage(
						cobbieSays(result == null ? "the AI did not answer" : result.friendlyError())));
				return;
			}

			lastState = ConnectionState.MODEL_AVAILABLE;
			lastStateDetail = "answered " + name + " at " + now;
			final ActionParser.ActionPlan plan = ActionParser.parse(result.content());
			server.execute(() -> deliver(sender, body, speakerId, name, text, plan));
		}).exceptionally(ex -> {
			inFlight.decrementAndGet();
			pending.remove(key);
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			noteRequestFailure(cause.toString());
			Cobbie.LOGGER.warn("Cobbie request blew up: {}", cause.toString());
			if (server != null) {
				server.execute(() -> sender.sendSystemMessage(
						cobbieSays("something went wrong in my head there")));
			}
			return null;
		});
	}

	/** Runs on the server thread: speak, offer the plan, store the turn. */
	private static void deliver(ServerPlayer player, CobbieEntity body, UUID speakerId, String speakerName,
			String said, ActionParser.ActionPlan plan) {
		if (body.isRemoved() || player.isRemoved()) return;

		// The answer goes to the person who asked, and to the owner if that is someone else.
		player.sendSystemMessage(cobbieSays(plan.answer()));
		UUID ownerId = body.getOwner();
		if (ownerId != null && !ownerId.equals(speakerId)) {
			ServerPlayer owner = body.owner();
			if (owner != null) {
				owner.sendSystemMessage(cobbieSays("(to " + speakerName + ") " + plan.answer()));
			}
		}
		if (!plan.problems().isEmpty()) {
			Cobbie.LOGGER.warn("Cobbie dropped actions from a reply: {}", plan.problems());
		}

		if (CobbieConfig.isMemoryEnabled()) {
			CobbieMemory.addTurn(speakerId, said, plan.answer());
		}

		// A lookup is not a world action: run it here, off-thread, and answer when the wiki does.
		for (ActionIntent intent : plan.actions()) {
			if (ActionVocabulary.LOOKUP.equals(intent.action())) {
				if (CobbieConfig.isMemoryEnabled()) CobbieMemory.setCurrentObjective(speakerId, "look up " + intent.target());
				WebLookup.search(intent.target()).thenAccept(answer -> {
					MinecraftServer server = player.level().getServer();
					if (server != null) {
						server.execute(() -> {
							player.sendSystemMessage(cobbieSays(answer));
							if (CobbieConfig.isMemoryEnabled()) {
								CobbieMemory.addTurn(speakerId, "look up " + intent.target(), answer);
							}
						});
					}
				});
				return;
			}
		}

		if (!plan.hasWorldAction()) return;

		List<ActionIntent> allowed = new ArrayList<>();
		List<String> blocked = new ArrayList<>();
		for (ActionIntent intent : plan.actions()) {
			if (!ActionVocabulary.isAllowed(intent.action())) {
				blocked.add(intent.action());
			} else if (!CobbieConfig.permits(intent.action())) {
				blocked.add(intent.action() + " (not permitted)");
			} else {
				allowed.add(intent);
			}
		}
		if (!blocked.isEmpty()) {
			player.sendSystemMessage(cobbieSays("I cannot do: " + String.join(", ", blocked)));
		}
		if (allowed.isEmpty()) return;

		// Busy? Then this is an addition to the job in hand, not a second queue entry: he keeps
		// working and folds the new request in, nearest target first.
		String refusal = body.isBusy()
				? body.offerAddition(said, allowed)
				: body.offerPlan(said, allowed);
		if (refusal != null) {
			player.sendSystemMessage(cobbieSays(refusal));
		} else if (CobbieConfig.isMemoryEnabled() && ownerId != null) {
			CobbieMemory.setCurrentObjective(ownerId, refusal == null ? said : "");
		}
	}

	private static List<LlmClient.Msg> conversationTurns(UUID speakerId) {
		if (!CobbieConfig.isMemoryEnabled()) return List.of();
		List<CobbieMemory.Message> history =
				CobbieMemory.history(speakerId, CobbieConfig.getMaxChatHistory());
		List<LlmClient.Msg> out = new ArrayList<>(history.size());
		for (CobbieMemory.Message m : history) {
			out.add(new LlmClient.Msg(m.role(), m.content()));
		}
		return out;
	}

	// ==================================================================
	// Owner commands that need no model
	// ==================================================================

	private static boolean isDirectCommand(String text) {
		String t = text.toLowerCase(Locale.ROOT).trim();
		return t.startsWith("cobbie follow") || t.startsWith("cobbie stay") || t.startsWith("cobbie stop")
				|| t.startsWith("cobbie cancel") || t.startsWith("cobbie come") || t.startsWith("cobbie wait")
				|| t.startsWith("cobbie inventory") || t.startsWith("cobbie bag") || t.startsWith("cobbie skills")
				|| t.startsWith("cobbie do ");
	}

	private static void handleDirectCommand(ServerPlayer sender, String text) {
		String t = text.toLowerCase(Locale.ROOT).trim();
		CobbieEntity body = companionOf(sender);
		if (body == null) {
			sender.sendSystemMessage(cobbieSays("spawn me with /spawncobbie first"));
			return;
		}
		UUID speakerId = sender.getUUID();
		boolean owner = body.isOwner(sender);

		if (t.startsWith("cobbie cancel") || t.startsWith("cobbie stop")) {
			int dropped = cancelRequestsFor(sender.getUUID());
			boolean stopped = body.cancelTask("owner said stop");
			sender.sendSystemMessage(cobbieSays(stopped || dropped > 0
					? "stopped (" + dropped + " pending thought(s) dropped)"
					: "I was not doing anything"));
			return;
		}
		if (t.startsWith("cobbie follow") || t.startsWith("cobbie come")) {
			body.startFollowing();
			body.enqueue(new ActionIntent(ActionVocabulary.FOLLOW, "", 0, 0, 0, ""));
			sender.sendSystemMessage(cobbieSays("following you"));
			return;
		}
		if (t.startsWith("cobbie stay") || t.startsWith("cobbie wait")) {
			body.stopFollowing();
			body.cancelTask("owner asked me to stay");
			body.enqueue(new ActionIntent(ActionVocabulary.STAY, "", 0, 0, 0, ""));
			sender.sendSystemMessage(cobbieSays("staying here"));
			return;
		}
		if (t.startsWith("cobbie inventory") || t.startsWith("cobbie bag")) {
			sender.sendSystemMessage(cobbieSays("I am carrying: " + body.describeInventory()));
			return;
		}
		if (t.startsWith("cobbie skills")) {
			sender.sendSystemMessage(cobbieSays("skills I know:\n" + SkillExecutor.describeAll()));
			return;
		}
		if (t.startsWith("cobbie do ") && owner) {
			String skill = text.trim().substring("cobbie do ".length()).trim();
			SkillExecutor.Outcome outcome = SkillExecutor.run(body, skill);
			sender.sendSystemMessage(cobbieSays(outcome.message()));
			return;
		}
		if (t.startsWith("cobbie do ")) {
			sender.sendSystemMessage(cobbieSays("only my owner can tell me to run a saved skill"));
		}
	}

	// ==================================================================
	// Plan results out — the closed half of the loop
	// ==================================================================

	/** Called from the entity when a plan settles. Queued; delivered on the next server tick. */
	public static void reportPlanSettled(CobbieEntity cobbie, TaskPlan plan) {
		if (cobbie == null || plan == null || cobbie.getOwner() == null) return;
		synchronized (reportQueue) {
			while (reportQueue.size() > 16) reportQueue.pollFirst();   // bounded, oldest dropped
			reportQueue.addLast(new Object[]{cobbie, plan.report()});
		}
	}

	/** Execute a recognised short command without the model in the loop. */
	private static void runImperative(ServerPlayer sender, CobbieEntity body, Imperatives.Hit hit) {
		switch (hit.verb()) {
			case "wave" -> {
				body.wave();
				body.say("here's a wave");
			}
			case "follow" -> {
				body.startFollowing();
				body.cancelTask("owner called me over");
				body.enqueue(new ActionIntent(ActionVocabulary.FOLLOW, "", 0, 0, 0, ""));
				body.say("coming");
			}
			case "stay" -> {
				body.stopFollowing();
				body.cancelTask("owner said stay");
				body.enqueue(new ActionIntent(ActionVocabulary.STAY, "", 0, 0, 0, ""));
				body.say("staying here");
			}
			case "collect" -> body.enqueue(new ActionIntent(ActionVocabulary.COLLECT, "", 0, 0, 0, ""));
			case "bag" -> sender.sendSystemMessage(cobbieSays("I am carrying: " + body.describeInventory()));
			case "drop" -> {
				// Urgent: whatever he was doing is parked and resumes afterwards, not thrown away.
				String refusal = body.offerUrgentPlan("drop " + hit.target(),
						List.of(new ActionIntent(ActionVocabulary.DROP, hit.target(), 0, 0, 0, "all")));
				if (refusal != null) sender.sendSystemMessage(cobbieSays(refusal));
			}
			case "lookup" -> {
				sender.sendSystemMessage(cobbieSays("looking that up..."));
				WebLookup.search(hit.target()).thenAccept(answer -> {
					MinecraftServer server = sender.level().getServer();
					if (server != null) {
						server.execute(() -> {
							sender.sendSystemMessage(cobbieSays(answer));
							if (CobbieConfig.isMemoryEnabled()) {
								CobbieMemory.addTurn(sender.getUUID(), "look up " + hit.target(), answer);
							}
						});
					}
				});
			}
			default -> { }
		}
	}

	private static void deliverReports(MinecraftServer server) {
		Object[] entry;
		synchronized (reportQueue) {
			entry = reportQueue.pollFirst();
		}
		if (entry == null) return;

		final CobbieEntity cobbie = (CobbieEntity) entry[0];
		final String report = (String) entry[1];

		server.execute(() -> {
			if (cobbie.isRemoved()) return;
			UUID ownerId = cobbie.getOwner();
			if (ownerId == null) return;
			ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);

			if (owner != null) {
				owner.sendSystemMessage(cobbieSays("result - " + shorten(report, 260)));
			}
			if (CobbieConfig.isMemoryEnabled()) {
				CobbieMemory.noteDiscovery(ownerId, shorten(report, 140));
				CobbieMemory.setCurrentObjective(ownerId, "");
			}

			TaskPlan finished = cobbie.lastPlan();
			if (finished != null) {
				SkillExecutor.recordForPlanRequest(finished.request(),
						finished.status() == TaskPlan.Status.COMPLETED);
			}
			learnFromPlan(cobbie, report);

			if (client != null && owner != null && CobbieConfig.isAutonomous()) {
				ask(cobbie, owner, server,
						CobbiePrompt.feedbackMessage(cobbie.currentTaskDescription(), report));
			}
		});
	}

	/** A clean multi-step run becomes a reusable skill, recorded relative to where he stood. */
	private static void learnFromPlan(CobbieEntity cobbie, String report) {
		TaskPlan plan = cobbie.lastPlan();
		if (plan == null || plan.status() != TaskPlan.Status.COMPLETED) return;
		BlockPos origin = cobbie.planOrigin();
		if (origin == null || plan.request().startsWith(SkillExecutor.REQUEST_PREFIX)) return;

		List<ActionStep> steps = new ArrayList<>();
		int worldSteps = 0;
		for (TaskStep step : plan.steps()) {
			if (step.status() != TaskStep.Status.DONE) return;   // only a clean run is worth learning
			ActionIntent intent = step.intent();
			if (!ActionVocabulary.isAllowed(intent.action())) return;
			if (ActionVocabulary.isWorldChanging(intent.action())) worldSteps++;
			steps.add(new ActionStep(intent.action(), intent.target(),
					intent.x() - origin.getX(), intent.y() - origin.getY(), intent.z() - origin.getZ(),
					"rel"));
		}
		if (worldSteps < 2 || steps.size() < 2) return;

		String name = skillNameFor(plan.request());
		if (SkillRegistry.saveSkill(name, "learned from: " + shorten(plan.request(), 90), steps)) {
			Cobbie.LOGGER.info("Cobbie learned the skill \"{}\" from: {}", name, report);
			ServerPlayer owner = cobbie.owner();
			if (owner != null) {
				owner.sendSystemMessage(cobbieSays("I learned how to do that - saved as \"" + name
						+ "\" (cobbie do " + name + ")"));
			}
			if (CobbieConfig.isMemoryEnabled()) {
				CobbieMemory.addSkill(cobbie.getOwner(), name);
			}
		}
	}

	private static String skillNameFor(String request) {
		String base = request == null || request.isBlank() ? "procedure"
				: request.trim().toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder();
		for (String word : base.split("[^a-z0-9]+")) {
			if (word.isEmpty() || word.length() < 3) continue;
			if (sb.length() > 0) sb.append('-');
			sb.append(word);
			if (sb.length() > 26) break;
		}
		String name = sb.length() == 0 ? "procedure" : sb.toString();
		return name.length() > 36 ? name.substring(0, 36) : name;
	}

	/** A second-person turn used to tell the model what really happened. */
	private static void ask(CobbieEntity cobbie, ServerPlayer listener, MinecraftServer server,
			String instruction) {
		UUID ownerId = cobbie.getOwner();
		if (ownerId == null || inFlight.get() >= CobbieConfig.getMaxConcurrentRequests()) return;
		inFlight.incrementAndGet();

		final String system = CobbiePrompt.systemPrompt(cobbie.perceive(),
				CobbieConfig.isMemoryEnabled() ? CobbieMemory.longTermSummary(ownerId) : "",
				"", SkillRegistry.describeForPrompt(4));

		client.complete(system, List.of(), instruction).thenAccept(result -> {
			inFlight.decrementAndGet();
			if (result == null || !result.success() || listener.isRemoved()) return;
			final ActionParser.ActionPlan plan = ActionParser.parse(result.content());
			server.execute(() -> {
				if (cobbie.isRemoved()) return;
				listener.sendSystemMessage(cobbieSays(shorten(plan.answer(), 300)));
				if (plan.hasWorldAction() && !cobbie.isBusy()) {
					List<ActionIntent> allowed = new ArrayList<>();
					for (ActionIntent intent : plan.actions()) {
						if (ActionVocabulary.isAllowed(intent.action()) && CobbieConfig.permits(intent.action())) {
							allowed.add(intent);
						}
					}
					if (!allowed.isEmpty()) cobbie.offerPlan("follow-up after results", allowed);
				}
			});
		}).exceptionally(ex -> {
			inFlight.decrementAndGet();
			return null;
		});
	}

	// ==================================================================
	// Helpers
	// ==================================================================

	/** Does this chat line count as speaking to Cobbie, for this speaker? */
	private static boolean addressedTo(ServerPlayer speaker, boolean mentioned) {
		if (CobbieConfig.isAllowedPlayer(speaker.getGameProfile().name())) return true;
		boolean owns = ownsNearby(speaker);
		switch (CobbieConfig.getChatMode()) {
			case MENTION:
				return mentioned;
			case OWNER_MENTION:
				return mentioned && owns;
			case ALWAYS_LISTEN:
				return true;
			case ALLOWLIST:
				return mentioned && owns;
			default:
				return mentioned;
		}
	}

	private static boolean ownsNearby(ServerPlayer speaker) {
		return !speaker.level().getEntitiesOfClass(CobbieEntity.class, nearbyBox(speaker.blockPosition()),
				e -> e.isOwner(speaker)).isEmpty();
	}

	private static AABB nearbyBox(BlockPos at) {
		return new AABB(at).inflate(Math.max(16, CobbieConfig.getNavigationRange()));
	}

	/** The companion this player may talk to: theirs if they have one, else an unowned stray. */
	public static CobbieEntity companionOf(ServerPlayer speaker) {
		List<CobbieEntity> around = speaker.level().getEntitiesOfClass(CobbieEntity.class,
				nearbyBox(speaker.blockPosition()), e -> !e.isRemoved());
		if (around.isEmpty()) return null;

		CobbieEntity mine = null;
		CobbieEntity stray = null;
		double mineDist = Double.MAX_VALUE;
		double strayDist = Double.MAX_VALUE;
		for (CobbieEntity c : around) {
			double d = c.distanceToSqr(speaker);
			if (c.isOwner(speaker)) {
				if (d < mineDist) {
					mineDist = d;
					mine = c;
				}
			} else if (c.getOwner() == null && d < strayDist) {
				strayDist = d;
				stray = c;
			}
		}
		if (mine != null) return mine;
		if (stray != null) return stray;
		// Someone else's companion: only the allow-list can reach it, never a random player.
		return CobbieConfig.isAllowedPlayer(speaker.getGameProfile().name()) ? around.get(0) : null;
	}

	private static Component cobbieSays(String text) {
		return Component.literal("§e[Cobbie] §f" + (text == null ? "..." : text));
	}

	private static String shorten(String s, int max) {
		if (s == null) return "";
		String v = s.replaceAll("\\s+", " ").trim();
		return v.length() <= max ? v : v.substring(0, max) + "...";
	}

	/** Holder so the snapshot type stays out of the lambda capture list. */
	private static final class WorldSnapshotHolder {
		private final com.rishi.cobbie.world.WorldSnapshot value;

		WorldSnapshotHolder(com.rishi.cobbie.world.WorldSnapshot v) {
			this.value = v;
		}
	}
}
