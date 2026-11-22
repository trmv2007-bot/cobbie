package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Multi-step work: one impossible step must not throw away the rest of the job, and a second
 * instruction given while he is busy must wait its turn rather than be refused.
 *
 * <p>Both were real failures: a plan died at step 1 and the remaining steps never ran, and an
 * instruction sent while he was walking was answered "already busy with…".
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbiePlanTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "mining", true);

            BlockPos owner = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos first = owner.offset(3, 0, 0);
            BlockPos second = owner.offset(0, 0, 3);

            server.computeOnServer(s -> {
                var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.DIRT, 16));
                // Two supported, empty spots for the two placements.
                for (BlockPos spot : List.of(first, second)) {
                    CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                            + " minecraft:air");
                    CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + (spot.getY() - 1) + " "
                            + spot.getZ() + " minecraft:stone");
                }
                return null;
            });

            // Step 2 is deliberately impossible: there is nothing to mine at that coordinate.
            BlockPos nothing = owner.offset(0, 20, 0);
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("put two blocks down and dig in the middle", List.of(
                            new ActionIntent(ActionVocabulary.PLACE, "minecraft:dirt",
                                    first.getX(), first.getY(), first.getZ(), ""),
                            new ActionIntent(ActionVocabulary.MINE, "minecraft:stone",
                                    nothing.getX(), nothing.getY(), nothing.getZ(), ""),
                            new ActionIntent(ActionVocabulary.PLACE, "minecraft:dirt",
                                    second.getX(), second.getY(), second.getZ(), ""))));
            if (refusal != null) throw new AssertionError("plan refused: " + refusal);

            try {
                server.waitFor(s -> s.overworld().getBlockState(first).is(Blocks.DIRT)
                        && s.overworld().getBlockState(second).is(Blocks.DIRT), 900);
            } catch (Throwable t) {
                throw new AssertionError("A failing middle step cost him the rest of the job: first="
                        + server.computeOnServer(s -> s.overworld().getBlockState(first).toString())
                        + " second="
                        + server.computeOnServer(s -> s.overworld().getBlockState(second).toString()), t);
            }

            // ---- a second instruction while he is busy ----
            BlockPos far = owner.offset(12, 0, 12);
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().interrupt();
                return null;
            });
            server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("go for a walk",
                    List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                            far.getX(), far.getY(), far.getZ(), ""))));
            context.waitTicks(5);

            String queued = server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().interrupt();
                return CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("wave at me",
                        List.of(new ActionIntent(ActionVocabulary.WAVE, "", 0, 0, 0, "")));
            });
            if (queued != null) {
                throw new AssertionError("A second instruction was refused instead of queued: " + queued);
            }

            // ---- steering mid-job ----
            BlockPos farAway = owner.offset(24, 0, 0);
            server.computeOnServer(s -> {
                var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.cancelTask("clean slate for the steering test");
                cobbie.offerPlan("walk far away", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                        farAway.getX(), farAway.getY(), farAway.getZ(), "")));
                return null;
            });
            context.waitTicks(20);

            context.runOnClient(mc -> mc.getConnection().sendChat("cobbie wave"));
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> c.isWaving()).orElse(false), 200);
            } catch (Throwable t) {
                throw new AssertionError("Steering failed: the typed wave did not interrupt the walk", t);
            }

            // ...and the interrupted walk must be picked back up, not abandoned.
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.blockPosition().distSqr(farAway) <= 9)
                        .orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("The walk was not resumed after steering; he is at "
                        + server.computeOnServer(s -> String.valueOf(
                        CobbieTestSupport.cobbie(s).map(c -> c.blockPosition()).orElse(null))), t);
            }

            // ---- "and get that too": new work joins the job in hand ----
            BlockPos seam = owner.offset(-6, 0, 0);
            BlockPos extra = owner.offset(-6, 0, 4);
            server.computeOnServer(s -> {
                var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.cancelTask("clean slate for the merge test");
                // Stand him back next to the owner: the previous scenario left him 24 blocks away,
                // which is past the range he will walk for a single block.
                var at = CobbieTestSupport.owner(s).blockPosition();
                cobbie.absSnapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
                cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
                for (BlockPos spot : List.of(seam, extra)) {
                    CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                            + " minecraft:stone");
                }
                return null;
            });

            String seamRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("mine the seam", List.of(new ActionIntent(ActionVocabulary.MINE,
                            "minecraft:stone", seam.getX(), seam.getY(), seam.getZ(), ""))));
            if (seamRefusal != null) throw new AssertionError("seam plan refused: " + seamRefusal);

            // One tick later the plan is definitely still running, which is the case being tested.
            context.waitTicks(1);
            String mergeRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerAddition("also get that one", List.of(new ActionIntent(ActionVocabulary.MINE,
                            "minecraft:stone", extra.getX(), extra.getY(), extra.getZ(), ""))));
            if (mergeRefusal != null) throw new AssertionError("addition refused: " + mergeRefusal);

            try {
                server.waitFor(s -> s.overworld().getBlockState(seam).isAir()
                        && s.overworld().getBlockState(extra).isAir(), 900);
            } catch (Throwable t) {
                throw new AssertionError("The added target was not mined inside the same job: seam="
                        + server.computeOnServer(s -> s.overworld().getBlockState(seam).toString())
                        + " extra="
                        + server.computeOnServer(s -> s.overworld().getBlockState(extra).toString()), t);
            }

            String job = server.computeOnServer(s -> {
                var plan = CobbieTestSupport.cobbie(s).orElseThrow().lastPlan();
                return plan == null ? "no plan" : plan.request() + " / " + plan.steps().size() + " steps";
            });

            System.out.println("[gametest] plan ok: survived a bad middle step, queued a second job, "
                    + "resumed a walk after a mid-job wave, and merged new work into the running job ("
                    + job + ")");
        }
    }
}
