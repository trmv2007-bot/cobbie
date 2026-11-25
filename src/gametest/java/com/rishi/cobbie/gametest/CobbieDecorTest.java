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
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.List;

/**
 * The decorative tail: the parts a hand can actually reach.
 *
 * <p>A probe came first and decided the shape of this slice. Signs are the only piece needing code — vanilla's
 * interaction opens an editing screen for a player, which the actor cannot use, so a sign can be placed
 * through {@code use} and never written through it. Item frames are already reachable with the same
 * gesture, which the probe confirmed before this test existed.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Decor}.
 */
public final class CobbieDecorTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieEntity spawn = CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.command(server, "cobbie stay");

            BlockPos signPos = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos pos = cobbie.blockPosition().offset(1, -1, 1);
                CobbieTestSupport.command(s, "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " minecraft:oak_sign[rotation=0]");
                return pos;
            });

            // --- 1. Write on the sign. ---
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("write welcome on the sign",
                            List.of(new ActionIntent(ActionVocabulary.WRITE, "welcome home", signPos.getX(),
                                    signPos.getY(), signPos.getZ(), ""))));
            if (refusal != null) {
                throw new AssertionError("The write plan was refused: " + refusal);
            }
            String written = awaitSignLine(server, signPos, 0);
            if (!"welcome home".equals(written)) {
                throw new AssertionError("The sign's first line reads \"" + written + "\": "
                        + lastReport(server));
            }

            // --- 2. A sign that is full says so instead of overwriting. ---
            // Five words of fifteen characters cannot fit four lines of fifteen, so the refusal has a
            // number in it rather than an opinion.
            String tooLong = "aaaaaaaaaaaaaaa bbbbbbbbbbbbbbb ccccccccccccccc ddddddddddddddd eeeeeeeeeeeeeee";
            server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("fill the sign", List.of(new ActionIntent(ActionVocabulary.WRITE,
                            tooLong, signPos.getX(), signPos.getY(), signPos.getZ(), ""))));
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String fullRefusal = lastFailure(server);
            if (fullRefusal == null || !fullRefusal.contains("too long")) {
                throw new AssertionError("Text longer than a sign was not refused for its length: "
                        + lastReport(server));
            }
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.offerPlan("second line", List.of(new ActionIntent(ActionVocabulary.WRITE,
                        "second line here", signPos.getX(), signPos.getY(), signPos.getZ(), "")));
                return null;
            });
            String second = awaitSignLine(server, signPos, 1);
            if (!"second line".equals(second)) {
                throw new AssertionError("The second write did not take the next empty line: line 1 reads \""
                        + second + "\"");
            }

            // --- 3. An item frame: place it with the frame in hand, put a diamond in, take it out. ---
            BlockPos wall = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos pos = cobbie.blockPosition().offset(-2, 0, 2);
                CobbieTestSupport.command(s, "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " minecraft:stone");
                return pos;
            });
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.ITEM_FRAME, 1));
                cobbie.addItem(new ItemStack(Items.DIAMOND, 1));
                return null;
            });
            String placeRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("hang the frame", List.of(new ActionIntent(ActionVocabulary.USE,
                            "minecraft:item_frame", wall.getX(), wall.getY(), wall.getZ(), "north"))));
            if (placeRefusal != null) {
                throw new AssertionError("Hanging the frame was refused: " + placeRefusal);
            }
            CobbieTestSupport.awaitPlanSettled(server, 600);
            var frame = awaitFrame(server, wall.north());
            if (frame == null) {
                throw new AssertionError("No item frame appeared beside the wall: " + lastReport(server));
            }
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("put the diamond in the frame",
                        List.of(new ActionIntent(ActionVocabulary.USE, "minecraft:diamond", 0, 0, 0,
                                "minecraft:item_frame")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String holds = awaitFrameItem(server, wall.north());
            if (!"minecraft:diamond".equals(holds)) {
                throw new AssertionError("The frame holds \"" + holds + "\" instead of the diamond: "
                        + lastReport(server));
            }
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("take it back out",
                        List.of(new ActionIntent(ActionVocabulary.USE, "", 0, 0, 0, "minecraft:item_frame")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String after = awaitFrameItem(server, wall.north());
            if (!"minecraft:air".equals(after) && !after.isEmpty()) {
                throw new AssertionError("The frame still holds \"" + after + "\" after taking it out: "
                        + lastReport(server));
            }
            int carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:diamond"));
            if (carried < 1) {
                throw new AssertionError("The diamond came out of the frame and vanished: he carries " + carried);
            }

            System.out.println("[gametest] decor ok: sign line 0 \"" + written + "\", line 1 \"" + second
                    + "\"; over-long text refused with its reason; frame held " + holds
                    + " and gave it back (" + carried + " diamond carried)");
            if (spawn == null) throw new AssertionError("no companion");
        }
    }

    private static String awaitSignLine(TestServerContext server, BlockPos pos, int line) {
        try {
            server.waitFor(s -> {
                var be = s.overworld().getBlockEntity(pos);
                if (!(be instanceof SignBlockEntity sign)) return false;
                var messages = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT)
                        .getMessages(false);
                return line < messages.size() && !messages.get(line).getString().isBlank();
            }, 600);
        } catch (Throwable t) {
            throw new AssertionError("Nothing appeared on line " + line + " of the sign at " + pos + ": "
                    + lastReport(server), t);
        }
        return server.computeOnServer(s -> {
            var sign = (SignBlockEntity) s.overworld().getBlockEntity(pos);
            return sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT)
                    .getMessages(false).get(line).getString();
        });
    }

    private static net.minecraft.world.entity.decoration.ItemFrame awaitFrame(TestServerContext server,
            BlockPos near) {
        try {
            server.waitFor(s -> !s.overworld().getEntitiesOfClass(
                    net.minecraft.world.entity.decoration.ItemFrame.class,
                    new net.minecraft.world.phys.AABB(near).inflate(1.5)).isEmpty(), 600);
        } catch (Throwable t) {
            return null;
        }
        return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ItemFrame.class,
                new net.minecraft.world.phys.AABB(near).inflate(1.5)).stream().findFirst().orElse(null));
    }

    private static String awaitFrameItem(TestServerContext server, BlockPos near) {
        try {
            server.waitFor(s -> s.overworld().getEntitiesOfClass(
                    net.minecraft.world.entity.decoration.ItemFrame.class,
                    new net.minecraft.world.phys.AABB(near).inflate(1.5)).stream()
                    .anyMatch(f -> !f.getItem().isEmpty() || true), 600);
        } catch (Throwable t) {
            throw new AssertionError("The frame was gone before it could be read: " + lastReport(server), t);
        }
        return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ItemFrame.class,
                new net.minecraft.world.phys.AABB(near).inflate(1.5)).stream()
                .map(f -> f.getItem().isEmpty() ? "minecraft:air"
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(f.getItem().getItem())
                                .toString())
                .findFirst().orElse("minecraft:air"));
    }

    /** The last plan's first failed step, which is where a refusal with a reason ends up. */
    private static String lastFailure(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            var plan = cobbie.lastPlan();
            if (plan == null) return null;
            for (var step : plan.steps()) {
                if (step.status() == com.rishi.cobbie.task.TaskStep.Status.FAILED) return step.result();
            }
            return null;
        });
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
