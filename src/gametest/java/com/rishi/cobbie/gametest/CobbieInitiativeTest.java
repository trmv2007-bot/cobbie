package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * He starts a project of his own — and does not, when the switch or the mode says otherwise.
 *
 * <p>Milestone 8, with both halves of the evidence the brief asks for. The positive: the owner stands
 * still, the companion is idle in CREATIVE, and within a bounded time a plan appears that nobody asked
 * for and reaches a terminal state. The negative matters as much: in ASSISTIVE he starts nothing, even
 * with the same world and the same waiting.
 *
 * <p>A project is a plan, so "terminal state" is the {@link TaskPlan}'s own notion of finished — he
 * either completes it or abandons it with a reason, and the plan says which.
 *
 * <p>Four phases in one world. The two negatives come first, while no project has run, because the
 * cooldown after a finished project would make them vacuous: a gate that cannot fire proves nothing,
 * which is exactly how the tick-predicate bug passed. The cooldown is then waited out in measured ticks
 * before each project phase.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Initiative}.
 */
public final class CobbieInitiativeTest implements FabricClientGameTest {

    /** The cooldown he keeps after a project, waited out in measured ticks rather than guessed. */
    private static final int COOLDOWN_TICKS = 2400;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collect", true);

            // --- 1. ASSISTIVE starts nothing. ---
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            int plansBefore = server.computeOnServer(CobbieInitiativeTest::planCount);
            int assistiveTicks = CobbieTestSupport.elapsed(server, 400);
            int plansAfter = server.computeOnServer(CobbieInitiativeTest::planCount);
            if (plansAfter != plansBefore) {
                throw new AssertionError("He started " + (plansAfter - plansBefore) + " project(s) in"
                        + " ASSISTIVE over " + assistiveTicks + " ticks, which is exactly what assistive"
                        + " means it must not do: " + lastReport(server));
            }

            // --- 2. Nothing is announced that cannot start. ---
            // Stone needs a pickaxe for its drop and he has none, so the only thing in sight is work he
            // could not begin. Phase 3 proves in the same run that the gate does fire, which is what
            // keeps this from passing on a gate that never woke.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() - 2 - i) + " " + base.getY() + " "
                            + (base.getZ() + 2) + " minecraft:stone");
                }
                return null;
            });
            CobbieTestSupport.command(server, "cobbie autonomy creative");
            int stoneBefore = server.computeOnServer(CobbieInitiativeTest::planCount);
            int stoneTicks = CobbieTestSupport.elapsed(server, 900);
            int stoneAfter = server.computeOnServer(CobbieInitiativeTest::planCount);
            if (stoneAfter != stoneBefore) {
                throw new AssertionError("He announced a project on blocks he cannot take without a"
                        + " pickaxe, which is a promise made before the check: " + lastReport(server));
            }
            System.out.println("[gametest] initiative: announced nothing with only un-mineable stone"
                    + " in sight over " + stoneTicks + " ticks, though the gate was running");
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() - 2 - i) + " " + base.getY() + " "
                            + (base.getZ() + 2) + " minecraft:air");
                }
                return null;
            });

            // Something worth gathering, only now: with it in the world earlier, the stone phase
            // above would have had a project to start and would have proved nothing.
            // Something worth gathering: a pile of logs beside him.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + 1 + i) + " " + base.getY() + " "
                            + (base.getZ() + 2) + " minecraft:oak_log");
                }
                return null;
            });

            // --- 3. CREATIVE starts one, and it ends. ---
            boolean started;
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.lastPlan() != null && !c.lastPlan().request().isBlank()
                                && c.lastPlan().request().startsWith("gathering"))
                        .orElse(false), 900);
                started = true;
            } catch (Throwable t) {
                started = false;
            }
            if (!started) {
                throw new AssertionError("No project started within 45s of being idle in CREATIVE; the"
                        + " last plan was " + lastReport(server));
            }
            String request = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().request());
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String status = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                throw new AssertionError("The project never reached a terminal state (" + status + "): "
                        + lastReport(server));
            }
            int logsLeft = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int left = 0;
                for (int i = 0; i < 3; i++) {
                    if (s.overworld().getBlockState(new BlockPos(base.getX() + 1 + i, base.getY(),
                            base.getZ() + 2)).is(net.minecraft.world.level.block.Blocks.OAK_LOG)) left++;
                }
                return left;
            });
            if (logsLeft != 0) {
                throw new AssertionError("He reported a finished gather but " + logsLeft
                        + " log(s) are still standing: " + lastReport(server));
            }

            System.out.println("[gametest] initiative ok: started no project in ASSISTIVE over "
                    + assistiveTicks + " ticks, then started \"" + request + "\" unprompted in CREATIVE"
                    + " and it reached " + status + " — " + lastReport(server));

            // --- 4. A project whose shape he decides, from material he carries. ---
            // Nothing gatherable is in sight now, so the only project available is the one he invents
            // from his bag — and it must actually stand afterwards. Building is default-deny like every
            // other switch, so the owner allows it first, and the cooldown his last project started is
            // waited out so this is not a pass on a gate that could not fire.
            int cooled = CobbieTestSupport.elapsed(server, COOLDOWN_TICKS + 200);
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.COBBLESTONE, 8));
                return null;
            });
            CobbieTestSupport.permit(server, "building", true);
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.lastPlan() != null && c.lastPlan().request().startsWith("building"))
                        .orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("He did not invent a project of his own within 45s after " + cooled
                        + " cooled ticks with a stack of cobblestone in his bag: " + lastReport(server), t);
            }
            String buildRequest = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().request());
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String buildStatus = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(buildStatus)) {
                throw new AssertionError("His own build never finished (" + buildStatus + "): "
                        + lastReport(server));
            }
            int placed = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int found = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            if (s.overworld().getBlockState(base.offset(dx, dy, dz))
                                    .is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) found++;
                        }
                    }
                }
                return found;
            });
            if (placed < 3) {
                throw new AssertionError("He reported a finished cairn but only " + placed
                        + " cobblestone block(s) stand: " + lastReport(server));
            }
            int carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countOf("minecraft:cobblestone"));
            if (carried > 5) {
                throw new AssertionError("He says he built a cairn but still carries " + carried
                        + " cobblestone — nothing was spent: " + lastReport(server));
            }
            System.out.println("[gametest] initiative: built on his own \"" + buildRequest + "\" — "
                    + placed + " cobblestone standing, " + carried + " left in the bag");
        }
    }

    /** How many plans have been created: only ever grows when something is handed to the runner. */
    private static int planCount(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(c -> {
            TaskPlan plan = c.lastPlan();
            return plan == null ? 0 : (int) plan.id();
        }).orElse(-1);
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
