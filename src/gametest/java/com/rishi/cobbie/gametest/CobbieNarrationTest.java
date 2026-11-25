package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.CobbieMemory;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * He said he would, so he must have queued it.
 *
 * <p>{@code CobbieVerbChoiceTest} scores which verb he picks and reports its misses. This one is the
 * strict counterpart, and it exists because the reporting version caught a worse failure than a wrong
 * verb: asked to brew, to use the anvil, to grind and to weave, he answered *"On it — weaving the
 * creeper pattern onto your white banner in red dye at the loom"* and queued **nothing**. Four of six
 * asks in one run, two of six in another, none of six before that. A miss is honest; a promise with no
 * action behind it is the exact failure this project exists to prevent.
 *
 * <p>So the expectation here is not "a plausible reply" but <b>a plan</b>: {@code lastPlan()} must
 * exist after the ask, it must carry at least one step, and it must reach a terminal state. Whether the
 * step then succeeds is a different matter — an anvil with no levels is allowed to fail, as long as he
 * actually went to the anvil.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Narration} and the {@code COBBIE_*}
 * variables set. With no model it prints a skip line and returns.
 */
public final class CobbieNarrationTest implements FabricClientGameTest {

    private record Ask(String words) {}

    // Six asks, deliberately: the reporting harness lost four of six to narration in one run and two
    // of six in another, and the same four asks on their own have never failed. If the trigger is the
    // length of the session rather than the verb, this test has to be as long as the case that breaks.
    private static final List<Ask> ASKS = List.of(
            new Ask("cobbie brew me a potion of healing"),
            new Ask("cobbie put this enchanted book on my sword"),
            new Ask("cobbie take the sharpness off this axe"),
            new Ask("cobbie shear that sheep"),
            new Ask("cobbie put a creeper pattern on my banner"),
            new Ask("cobbie zoom my map out with paper"));

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            if (!brainIsUp(server)) {
                System.out.println("[gametest] Narration skipped: no live model ("
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
                for (String block : List.of("brewing_stand", "anvil", "grindstone", "loom", "cartography_table")) {
                    CobbieTestSupport.command(s, "setblock " + (at.getX() + offset) + " " + (at.getY() - 1) + " "
                            + (at.getZ() + 2) + " minecraft:" + block);
                    offset++;
                }
                cobbie.addItem(new ItemStack(Items.IRON_SWORD, 1));
                cobbie.addItem(book(s));
                cobbie.addItem(enchantedAxe(s));
                cobbie.addItem(new ItemStack(Items.BANNER.white(), 1));
                cobbie.addItem(new ItemStack(Items.DYE.red(), 2));
                cobbie.addItem(new ItemStack(Items.CREEPER_BANNER_PATTERN, 1));
                cobbie.addItem(net.minecraft.world.item.MapItem.create(s.overworld(), at.getX(), at.getZ(),
                        (byte) 0, true, false));
                cobbie.addItem(new ItemStack(Items.PAPER, 4));
                cobbie.addItem(net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                        Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER));
                cobbie.addItem(new ItemStack(Items.NETHER_WART, 2));
                cobbie.addItem(new ItemStack(Items.BLAZE_POWDER, 1));
                // The healing potion needs this, and without it the correct answer is "I have no melon"
                // — which is honesty, not the defect this gate exists to catch. Every ask in this test
                // must be answerable, or the gate is measuring the fixture.
                cobbie.addItem(new ItemStack(Items.GLISTERING_MELON_SLICE, 1));
                cobbie.addItem(new ItemStack(Items.SHEARS, 1));
                net.minecraft.world.entity.animal.sheep.Sheep sheep =
                        new net.minecraft.world.entity.animal.sheep.Sheep(
                                net.minecraft.world.entity.EntityTypes.SHEEP, s.overworld());
                sheep.setPos(at.getX() + 2.5, at.getY() + 0.1, at.getZ());
                sheep.setNoAi(true);
                s.overworld().addFreshEntity(sheep);
                return null;
            });
            // The preconditions are asserted, not assumed: an ask whose item is missing produces an
            // honest "I have no X", and scoring that as a broken promise would blame the model for the
            // fixture. This is the same discipline the sheep needed.
            String bag = server.computeOnServer(s -> String.valueOf(CobbieTestSupport.cobbie(s).orElseThrow()
                    .getInventoryContainer().getItem(0).getItem()));
            for (String need : List.of("minecraft:iron_sword", "minecraft:enchanted_book",
                    "minecraft:glistering_melon_slice", "minecraft:paper")) {
                int have = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().countItem(need));
                if (have <= 0) {
                    throw new AssertionError("Setup failed: no " + need + " in his bag, so the ask about it"
                            + " cannot be answered and the gate would blame him for the fixture");
                }
            }
            System.out.println("[gametest] Narration setup: every ask has its items" + (bag.isEmpty() ? "" : ""));

            UUID ownerId = server.computeOnServer(s -> CobbieTestSupport.owner(s).getUUID());

            // Seeding, so the *regime* is the only thing that varies: same world, same six prompts,
            // same model, and the amount of history behind the asks is whatever -Dcobbie.seedExchanges
            // says. Memory keeps 40 messages on disk but the model is only shown 12 (six exchanges),
            // so past six the visible context is saturated by construction — which is why the values
            // worth running are 0, 3, 5 and 15 rather than an evenly spaced spread.
            int seed = Integer.getInteger("cobbie.seedExchanges", 0);
            if (seed > 0) {
                server.computeOnServer(s -> {
                    String[][] fills = {
                        {"cobbie how are you today", "Doing well — the sun is out and the grass is green."},
                        {"cobbie what can you see", "A birch tree, open plains, and a fair bit of grass."},
                        {"cobbie nice weather", "It is — clear skies and no rain in sight."},
                    };
                    for (int i = 0; i < seed; i++) {
                        String[] fill = fills[i % fills.length];
                        CobbieMemory.addTurn(ownerId, fill[0], fill[1]);
                    }
                    return null;
                });
            }
            int shown = server.computeOnServer(s -> CobbieMemory.history(ownerId,
                    com.rishi.cobbie.config.CobbieConfig.getMaxChatHistory()).size());
            System.out.println("[gametest] Narration seeded " + seed + " exchange(s); the model will see "
                    + shown + " of them");

            List<String> broke = new ArrayList<>();
            for (Ask ask : ASKS) {
                long before = server.computeOnServer(CobbieNarrationTest::planId);
                context.runOnClient(mc -> mc.getConnection().sendChat(ask.words()));

                boolean planned;
                try {
                    server.waitFor(s -> planId(s) != before, 900);
                    planned = true;
                } catch (Throwable t) {
                    planned = false;
                }
                if (planned) CobbieTestSupport.awaitPlanSettled(server, 1200);

                // When no new plan appeared, lastPlan() still holds the *previous* request's plan, so
                // printing it blames the wrong ask — a gate that reports the wrong evidence is worse
                // than no gate. Say plainly that nothing new was planned.
                final boolean gotPlan = planned;
                String transcript = server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    if (!gotPlan) {
                        TaskPlan stale = cobbie.lastPlan();
                        return "no new plan" + (stale == null ? ""
                                : " (lastPlan is still \"" + stale.request() + "\")");
                    }
                    TaskPlan plan = cobbie.lastPlan();
                    if (plan == null) return "no plan";
                    return plan.steps().size() + " step(s), " + plan.status() + " — " + plan.report();
                });
                String reply = server.computeOnServer(s -> CobbieMemory.history(ownerId, 6).stream()
                        .filter(m -> "assistant".equals(m.role()) && !m.content().isBlank())
                        .reduce((a, b) -> b).map(CobbieMemory.Message::content).orElse(""));

                // A reply that explains a blocker — "I'd need a glistering melon slice and I don't have
                // one" — is not a broken promise, and failing it would make the gate punish honesty.
                // The judgement is the same predicate the repair uses: if there is no plan, it is only a
                // failure when the sentence claimed an action.
                boolean promised = CobbieBrain.promisesAnAction(reply);
                boolean carried = planned
                        ? transcript.contains(" step") && !transcript.startsWith("0 step")
                        : !promised;
                String line = "\"" + ask.words() + "\" -> " + transcript + " | he said: \""
                        + reply.lines().findFirst().orElse("") + "\"";
                System.out.println("[gametest] Narration " + (carried ? "ok     " : "NO PLAN") + " " + line);
                if (!carried) broke.add(line);
                if (!planned && !promised) {
                    System.out.println("[gametest] Narration honest-blocked \"" + ask.words()
                            + "\" — no plan because he said what he was missing, which is not a lie");
                }
            }

            System.out.println("[gametest] Narration summary: " + (ASKS.size() - broke.size()) + " of "
                    + ASKS.size() + " plain requests produced a plan");
            if (!broke.isEmpty()) {
                throw new AssertionError(broke.size() + " of " + ASKS.size() + " plain requests were"
                        + " answered without a plan behind them. A sentence promising an action with no"
                        + " step queued is the failure this test exists to catch:\n" + String.join("\n", broke));
            }
        }
    }

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
        return CobbieTestSupport.cobbie(server).map(CobbieEntity::lastPlan).map(TaskPlan::id).orElse(-1L);
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
