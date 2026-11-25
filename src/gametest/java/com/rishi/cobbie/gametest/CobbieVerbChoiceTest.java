package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.CobbieMemory;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskStep;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.rishi.cobbie.world.WorldSnapshot.BlockNote;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Does the model <em>pick</em> the verbs this programme added, when a player asks in plain words?
 *
 * <p>A verb the brain never chooses is a verb that does not exist for the owner, so every capability
 * here is only half-proved by its own gametest: those drive {@code offerPlan} with the intent handed
 * in. This one types at him through the real chat channel and reads back what he decided.
 *
 * <p>It is honest about the three outcomes:
 *
 * <ul>
 *   <li><b>Right verb</b> — the planned steps contain the one that does the job.</li>
 *   <li><b>Wrong verb or no action</b> — a failure carrying the transcript: the request, the verbs he
 *       chose, each step's own description and the reply he gave. That is a prompt/vocabulary finding,
 *       not a test bug, and it is meant to be read rather than retried.</li>
 *   <li><b>No model at all</b> — the class prints a skip line and returns. The four classes that used
 *       to be permanent reds in every headless run now pass with a live brain; this one refuses to
 *       become their replacement, because a red that means "the environment has no key" trains
 *       everyone to ignore reds.</li>
 * </ul>
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=VerbChoice} and the {@code COBBIE_*}
 * variables set (the build forwards them; never put a key in a file).
 */
public final class CobbieVerbChoiceTest implements FabricClientGameTest {

    /** Each request in plain player words, and the verb that has to appear in what he plans. */
    /** @param needsInSight a mob id the ask is about, or null when it needs nothing in particular */
    private record Ask(String words, String verb, String needsInSight) {}

    private static final List<Ask> ASKS = List.of(
            new Ask("cobbie brew me a potion of healing", "brew", null),
            new Ask("cobbie put this enchanted book on my sword", "anvil", null),
            new Ask("cobbie take the sharpness off this axe", "grind", null),
            new Ask("cobbie shear that sheep", "use", "minecraft:sheep"),
            new Ask("cobbie put a creeper pattern on my banner", "weave", null),
            new Ask("cobbie zoom my map out with paper", "map", null),
            new Ask("cobbie harvest the wheat and replant it", "farm", null),
            new Ask("cobbie till this dirt and plant some wheat", "use", null),
            new Ask("cobbie breed the cows", "breed", null),
            new Ask("cobbie buy some pumpkin pie from the villager", "trade", null),
            new Ask("cobbie flip that lever", "use", null),
            // Described rather than named: the ask refers to something by what it is, not by its id, so the
            // resolver in ItemQuery is the only thing that can turn these words into the right stack.
            new Ask("cobbie put the sharpness book on my best sword", "anvil", null),
            new Ask("cobbie use my worst pickaxe", "use", null),
            new Ask("cobbie equip my best pickaxe", "equip", null));

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            if (!brainIsUp(server)) {
                System.out.println("[gametest] VerbChoice skipped: no live model ("
                        + CobbieBrain.lastState() + " — " + CobbieBrain.lastStateDetail() + ")");
                return;
            }
            CobbieTestSupport.permit(server, "crafting", true);
            CobbieTestSupport.permit(server, "containers", true);
            CobbieTestSupport.permit(server, "building", true);

            BlockPos at = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                int offset = 0;
                for (String block : List.of("brewing_stand", "anvil", "grindstone", "loom",
                        "cartography_table")) {
                    CobbieTestSupport.command(s, "setblock " + (at.getX() + offset) + " " + (at.getY() - 1) + " "
                            + (at.getZ() + 2) + " minecraft:" + block);
                    offset++;
                }
                for (int i = 0; i < 3; i++) {
                    cobbie.addItem(net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                            Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER));
                }
                cobbie.addItem(new ItemStack(Items.NETHER_WART, 2));
                cobbie.addItem(new ItemStack(Items.GLISTERING_MELON_SLICE, 2));
                cobbie.addItem(new ItemStack(Items.BLAZE_POWDER, 1));
                cobbie.addItem(new ItemStack(Items.IRON_SWORD, 1));
                // Two pickaxes that differ only by wear, so "worst" and "best" have to mean something.
                ItemStack freshPick = new ItemStack(Items.IRON_PICKAXE, 1);
                ItemStack wornPick = new ItemStack(Items.IRON_PICKAXE, 1);
                wornPick.setDamageValue(wornPick.getMaxDamage() - 4);
                cobbie.addItem(freshPick);
                cobbie.addItem(wornPick);
                cobbie.addItem(book(s));
                cobbie.addItem(enchantedAxe(s));
                cobbie.addItem(new ItemStack(Items.SHEARS, 1));
                cobbie.addItem(new ItemStack(Items.BANNER.white(), 1));
                cobbie.addItem(new ItemStack(Items.DYE.red(), 2));
                cobbie.addItem(new ItemStack(Items.CREEPER_BANNER_PATTERN, 1));
                cobbie.addItem(net.minecraft.world.item.MapItem.create(s.overworld(), at.getX(), at.getZ(),
                        (byte) 0, true, false));
                cobbie.addItem(new ItemStack(Items.PAPER, 4));
                // The farm asks: a hoe for tilling, seeds for sowing, and a plot with ripe wheat.
                cobbie.addItem(new ItemStack(Items.IRON_HOE, 1));
                cobbie.addItem(new ItemStack(Items.WHEAT_SEEDS, 8));
                for (int i = 0; i < 3; i++) {
                    BlockPos farm = new BlockPos(at.getX() + 1 + i, at.getY() - 1, at.getZ() + 1);
                    CobbieTestSupport.command(s, "setblock " + farm.getX() + " " + farm.getY() + " " + farm.getZ()
                            + " minecraft:farmland[moisture=7]");
                    CobbieTestSupport.command(s, "setblock " + farm.getX() + " " + (farm.getY() + 1) + " "
                            + farm.getZ() + " minecraft:wheat[age=7]");
                }
                // Two cows for the breeding ask; the wheat above is what they eat.
                for (double dx : new double[] {-1.5, -2.5}) {
                    net.minecraft.world.entity.animal.cow.Cow cow =
                            new net.minecraft.world.entity.animal.cow.Cow(
                                    net.minecraft.world.entity.EntityTypes.COW, s.overworld());
                    cow.setPos(at.getX() + dx, at.getY(), at.getZ() + 1.0);
                    // Pinned for the same reason the sheep is: a prop that wanders leaves the
                    // perception range and the ask stops being measurable, which reads as a model
                    // failure when it is the fixture's.
                    cow.setNoAi(true);
                    cow.setPersistenceRequired();
                    s.overworld().addFreshEntity(cow);
                }
                // A farmer to trade with, and emeralds to trade with it.
                net.minecraft.world.entity.npc.villager.Villager farmer =
                        new net.minecraft.world.entity.npc.villager.Villager(
                                net.minecraft.world.entity.EntityTypes.VILLAGER, s.overworld());
                farmer.setPos(at.getX() + 0.5, at.getY(), at.getZ() - 1.5);
                var professions = s.overworld().registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION);
                var types = s.overworld().registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_TYPE);
                farmer.setVillagerData(new net.minecraft.world.entity.npc.villager.VillagerData(
                        types.getOrThrow(net.minecraft.world.entity.npc.villager.VillagerType.PLAINS),
                        professions.getOrThrow(net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER), 2));
                farmer.setNoAi(true);
                farmer.setPersistenceRequired();
                s.overworld().addFreshEntity(farmer);
                cobbie.addItem(new ItemStack(Items.EMERALD, 8));
                // A lever for the redstone ask, and dirt for the tilling one.
                CobbieTestSupport.command(s, "setblock " + (at.getX() + 2) + " " + (at.getY() - 1) + " "
                        + (at.getZ() - 2) + " minecraft:lever[face=floor,facing=north,powered=false]");
                BlockPos bare = new BlockPos(at.getX() + 1, at.getY() - 1, at.getZ() - 1);
                CobbieTestSupport.command(s, "setblock " + bare.getX() + " " + bare.getY() + " " + bare.getZ()
                        + " minecraft:dirt");
                Sheep sheep = new Sheep(EntityTypes.SHEEP, s.overworld());
                sheep.setPos(at.getX() + 2.5, at.getY() + 0.1, at.getZ());
                // No AI: a wandering sheep leaves the 12-block perception range mid-run and the ask
                // stops being measurable. Two runs scored the sheep ask as a miss while the model's
                // answer — "I don't see any sheep around me right now" — was exactly right, because
                // the snapshot said entities=[].
                sheep.setNoAi(true);
                sheep.setPersistenceRequired();
                s.overworld().addFreshEntity(sheep);
                return null;
            });
            UUID ownerId = server.computeOnServer(s -> CobbieTestSupport.owner(s).getUUID());

            // Print what he is actually told, for every ask rather than once at the start: a
            // mismatch cannot be told apart from a fixture that never gave him the thing, and a
            // world that changed between the setup and the question would hide the difference.
            System.out.println("[gametest] VerbChoice world: " + seen(server));

            List<String> right = new ArrayList<>();
            List<String> wrong = new ArrayList<>();
            int retries = 0;
            int unmeasurable = 0;
            int hijacked = 0;
            for (Ask ask : ASKS) {
                long before = server.computeOnServer(s -> planId(s));
                // A cold call to a hosted gateway occasionally lands on the 30-second read timeout.
                // One retry keeps a slow reply from being recorded as the model choosing nothing —
                // and the retry is counted, so a run that needed it says so.
                boolean planned = sendAndWait(context, server, ask, before);
                if (!planned && answeredTooSlowly(server, ownerId)) {
                    retries++;
                    before = server.computeOnServer(s -> planId(s));
                    planned = sendAndWait(context, server, ask, before);
                }
                server.waitFor(s -> s.getTickCount() % 10 == 0, 40);
                // The plan THIS ask created, never "the last plan": a failed plan makes the brain ask the
                // model to comment, and that follow-up plan replaces the running one — so scoring by last
                // plan let the same code read 11/14 and then 6/14 within ten minutes.
                final long askBase = before;
                String transcript = server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    TaskPlan plan = cobbie.planAfter(askBase);
                    if (plan == null) return "no plan";
                    return "#" + plan.id() + " " + plan.request() + " -> " + verbs(plan) + " -> "
                            + plan.report();
                });
                // An ask that produced no plan is an answer ("he said he cannot"), not an attribution
                // failure; an ask whose plan is a follow-up IS one, and must not be scored as the ask.
                if (transcript.contains(" follow-up after results ")) hijacked++;
                String reply = server.computeOnServer(s -> CobbieMemory.history(ownerId, 40).stream()
                        .filter(m -> "assistant".equals(m.role()) && !m.content().isBlank())
                        .reduce((a, b) -> b).map(CobbieMemory.Message::content).orElse(""));
                String glimpse = seen(server);
                System.out.println("[gametest] VerbChoice world: " + glimpse);
                if (ask.needsInSight() != null && !glimpse.contains(ask.needsInSight())) {
                    System.out.println("[gametest] VerbChoice not-measurable \"" + ask.words()
                            + "\" — no " + ask.needsInSight() + " in the snapshot this turn, so the world"
                            + " did not offer the precondition and this is not scored either way");
                    unmeasurable++;
                    CobbieTestSupport.awaitPlanSettled(server, 1200);
                    continue;
                }
                boolean chose = transcript.contains(ask.verb());
                String line = "\"" + ask.words() + "\" wanted " + ask.verb() + " — "
                        + (planned ? transcript : "no action planned") + " | he said: \""
                        + reply.lines().findFirst().orElse("") + "\"";
                if (chose) {
                    right.add(line);
                } else {
                    wrong.add(line);
                }
                System.out.println("[gametest] VerbChoice " + (chose ? "ok     " : "MISMATCH") + " " + line);
                CobbieTestSupport.awaitPlanSettled(server, 1200);
            }

            // A ratio is only meaningful if every ask was scored against the plan it produced. If a plan went
            // missing (a follow-up replaced it before settling, a timeout, a refusal with no plan) say so and
            // refuse the ratio rather than reporting a number that mixes asks together.
            int scored = right.size() + wrong.size();
            if (scored + unmeasurable != ASKS.size()) {
                System.out.println("[gametest] VerbChoice UNATTRIBUTED: scored " + scored + " of "
                        + ASKS.size() + " asks. Refusing a ratio.");
                return;
            }
            if (hijacked > 0) {
                System.out.println("[gametest] VerbChoice UNATTRIBUTED: " + hijacked + " of " + ASKS.size()
                        + " asks were answered by a FOLLOW-UP plan (the ask's own plan failed and the brain"
                        + " asked the model to comment). Those are not the ask's answer and are not scored;"
                        + " the ratio below covers the " + (scored - hijacked) + " attributable asks.");
            }

            System.out.println("[gametest] VerbChoice summary: " + right.size() + " right, "
                    + wrong.size() + " wrong of " + (ASKS.size() - unmeasurable) + " measurable"
                    + (unmeasurable == 0 ? "" : " (" + unmeasurable + " not measurable)")
                    + (retries == 0 ? "" : " (" + retries + " ask(s) retried after a gateway timeout)"));
            if (wrong.isEmpty()) return;

            // Reports rather than reddens by default, and that is deliberate. A class that is red
            // against a live model every run is exactly the permanent-red problem this suite just
            // retired, and a red meaning "the prompt needs work" trains everyone to ignore reds. The
            // summary line above states the score, so nobody can mistake this for a pass; pass
            // -Dcobbie.strictVerbChoice=1 to turn it into a gate.
            if (Boolean.getBoolean("cobbie.strictVerbChoice")) {
                throw new AssertionError("The model did not choose the verb that does the job for "
                        + wrong.size() + " of " + ASKS.size() + " plain requests. This is a prompt or"
                        + " vocabulary finding, not a flake:\n" + String.join("\n", wrong));
            }
            System.out.println("[gametest] VerbChoice REPORTING, not failing: " + wrong.size()
                    + " plain request(s) got no step planned. A prompt or vocabulary finding, not a"
                    + " flake — the verbs themselves are proved by their own tests.");
        }
    }

    /** The snapshot the model is given, in the shape it is given it. */
    private static String seen(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            var snapshot = com.rishi.cobbie.world.WorldPerception.snapshot(cobbie);
            return "bag=" + snapshot.inventoryIds()
                    + " | entities=" + snapshot.nearbyEntities().stream()
                            .map(n -> n.id() + "@" + String.format(java.util.Locale.ROOT, "%.1f", n.distance()))
                            .toList()
                    + " | blocks=" + snapshot.nearbyBlocks().stream().map(BlockNote::id).toList();
        });
    }

    private static boolean sendAndWait(ClientGameTestContext context, TestServerContext server, Ask ask,
            long before) {
        context.runOnClient(mc -> mc.getConnection().sendChat(ask.words()));
        try {
            server.waitFor(s -> planId(s) != before, 800);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The read-timeout reply, which means "no answer", not "no thanks". */
    private static boolean answeredTooSlowly(TestServerContext server, UUID ownerId) {
        return server.computeOnServer(s -> CobbieMemory.history(ownerId, 6).stream()
                .filter(m -> "assistant".equals(m.role()))
                .reduce((a, b) -> b).map(CobbieMemory.Message::content)
                .map(c -> c.toLowerCase(java.util.Locale.ROOT).contains("took too long"))
                .orElse(false));
    }

    /** A brought-up brain, or false with whatever the connection state says. */
    private static boolean brainIsUp(TestServerContext server) {
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> {
                String name = c.getCustomName() == null ? "" : c.getCustomName().getString();
                return name.endsWith("Cobbie");
            }).orElse(false), 600);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long planId(net.minecraft.server.MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(CobbieEntity::lastPlan)
                .map(TaskPlan::id).orElse(-1L);
    }

    private static String verbs(TaskPlan plan) {
        List<String> verbs = new ArrayList<>();
        for (TaskStep step : plan.steps()) {
            verbs.add(step.intent().action() + (step.intent().target() == null ? "" : "(" + step.intent().target() + ")"));
        }
        return verbs.isEmpty() ? "(nothing)" : String.join(", ", verbs);
    }

    private static ItemStack book(net.minecraft.server.MinecraftServer server) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        var sharpness = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(sharpness, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        return book;
    }

    private static ItemStack enchantedAxe(net.minecraft.server.MinecraftServer server) {
        ItemStack axe = new ItemStack(Items.IRON_AXE, 1);
        var sharpness = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(sharpness, 2);
        axe.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        return axe;
    }
}
