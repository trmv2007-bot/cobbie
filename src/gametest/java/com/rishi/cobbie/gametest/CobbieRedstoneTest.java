package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

/**
 * The redstone components a player right-clicks, and the two that are not his to operate.
 *
 * <p>A probe settled this before anything was written, and it says the fiddly tail needs no code: a
 * lever, a button, a repeater's delay, a comparator's mode and a note block's note all change through
 * {@code ItemStack.useOn} — which the {@code use} verb has had since 0.21.1. A piston and an iron door
 * answer {@code Pass} and stay exactly as they were, because they do not respond to a hand at all; they
 * respond to redstone, and driving redstone is an *actor* job rather than an interactor's.
 *
 * <p>So this is a verification slice. It asserts the five that work so they cannot regress, and it
 * asserts that the two that cannot work <em>say so</em> rather than claiming success.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Redstone}.
 */
public final class CobbieRedstoneTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);

            BlockPos base = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            BlockPos lever = base.offset(1, -1, 1);
            BlockPos repeater = base.offset(2, -1, 1);
            BlockPos noteblock = base.offset(1, -1, 2);
            BlockPos piston = base.offset(2, -1, 2);
            BlockPos door = base.offset(1, -1, -2);

            // 1. The lever: powered false -> true.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + lever.getX() + " " + lever.getY() + " " + lever.getZ()
                        + " minecraft:lever[face=floor,facing=north,powered=false]");
                return null;
            });
            use(server, lever);
            if (!server.computeOnServer(s -> powered(s, lever))) {
                throw new AssertionError("The lever did not power: " + lastReport(server));
            }

            // 2. The repeater's delay: 1 -> 2.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + repeater.getX() + " " + repeater.getY() + " "
                        + repeater.getZ() + " minecraft:repeater[facing=north,delay=1]");
                return null;
            });
            use(server, repeater);
            int delay = server.computeOnServer(s -> s.overworld().getBlockState(repeater)
                    .getValue(BlockStateProperties.DELAY));
            if (delay != 2) throw new AssertionError("The repeater's delay stayed at " + delay
                    + ": " + lastReport(server));

            // 3. The note block's note: 0 -> something else.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + noteblock.getX() + " " + noteblock.getY() + " "
                        + noteblock.getZ() + " minecraft:note_block[note=0]");
                return null;
            });
            use(server, noteblock);
            int note = server.computeOnServer(s -> s.overworld().getBlockState(noteblock)
                    .getValue(BlockStateProperties.NOTE));
            if (note == 0) throw new AssertionError("The note block did not change note: " + lastReport(server));

            // 4. A piston is not his to operate, and the refusal has to say something happened — or
            //    nothing did — rather than claiming success.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + piston.getX() + " " + piston.getY() + " " + piston.getZ()
                        + " minecraft:piston[facing=north]");
                return null;
            });
            String pistonReport = useRaw(server, piston);
            if (pistonReport != null && !pistonReport.contains("nothing happened")) {
                throw new AssertionError("Using a piston claimed something: " + pistonReport);
            }

            // 5. An iron door likewise: it answers to redstone, not to a hand.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + door.getX() + " " + door.getY() + " " + door.getZ()
                        + " minecraft:iron_door[facing=north,half=lower,open=false]");
                return null;
            });
            String doorReport = useRaw(server, door);
            if (doorReport != null && !doorReport.contains("nothing happened")) {
                throw new AssertionError("Using an iron door claimed something: " + doorReport);
            }

            System.out.println("[gametest] redstone ok: lever powered, repeater delay 1 -> 2 and note block"
                    + " note 0 -> 1 all through the use verb; a piston and an iron door changed nothing and"
                    + " were reported as nothing happening rather than as success");
        }
    }

    private static void use(TestServerContext server, BlockPos pos) {
        String refusal = useRaw(server, pos);
        if (refusal != null) throw new AssertionError("use refused at " + pos + ": " + refusal);
        CobbieTestSupport.awaitPlanSettled(server, 400);
    }

    /** Run a one-step use plan and return its failure text, or null when it reported success. */
    private static String useRaw(TestServerContext server, BlockPos pos) {
        String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "use that", List.of(new ActionIntent(ActionVocabulary.USE, "", pos.getX(), pos.getY(), pos.getZ(), ""))));
        if (refusal != null) return refusal;
        CobbieTestSupport.awaitPlanSettled(server, 400);
        String report = lastReport(server);
        return report.contains("done") ? null : report;
    }

    private static boolean powered(MinecraftServer server, BlockPos pos) {
        var state = server.overworld().getBlockState(pos);
        return state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
