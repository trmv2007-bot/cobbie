package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.entity.CobbieEntities;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared setup for the in-game tests: a normal-preset world, the owner, and the one companion that
 * belongs to them. Every test drives Cobbie the way a player does — commands are dispatched from
 * the owner's command source, because a console source cannot spawn or own a companion.
 */
final class CobbieTestSupport {

    private CobbieTestSupport() {
    }

    static TestSingleplayerContext createWorld(ClientGameTestContext context, Difficulty difficulty,
                                               WorldCreationUiState.SelectedGameMode mode) {
        return createWorld(context, difficulty, mode, WorldPresets.NORMAL);
    }

    /**
     * Action tests use a flat world: level ground with no hillside between him and the block means a
     * red test is about his pathfinding and not about the terrain he happened to spawn in.
     */
    static TestSingleplayerContext createFlatWorld(ClientGameTestContext context, Difficulty difficulty,
                                                   WorldCreationUiState.SelectedGameMode mode) {
        return createWorld(context, difficulty, mode, WorldPresets.FLAT);
    }

    private static TestSingleplayerContext createWorld(ClientGameTestContext context, Difficulty difficulty,
                                                       WorldCreationUiState.SelectedGameMode mode,
                                                       net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> preset) {
        return context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    state.getNormalPresetList().stream()
                            .filter(e -> e.preset().unwrapKey().map(preset::equals).orElse(false))
                            .findFirst()
                            .ifPresent(state::setWorldType);
                    state.setGameMode(mode);
                    state.setDifficulty(difficulty);
                    state.setAllowCommands(true);
                })
                .create();
    }

    static void awaitWorld(ClientGameTestContext context) {
        context.waitFor(mc -> mc.player != null && mc.level != null, 400);
    }

    static ServerPlayer owner(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(owner(server).createCommandSourceStack(), command);
    }

    static void command(TestServerContext server, String command) {
        server.computeOnServer(s -> {
            CobbieTestSupport.command(s, command);
            return null;
        });
    }

    static Optional<CobbieEntity> cobbie(MinecraftServer server) {
        return cobbie(server, 48);
    }

    /**
     * The companion within {@code radius} of the owner. A test that deliberately walks him a long
     * way off needs a wider box than the everyday 48, or it finds nothing exactly when the
     * behaviour under test is "he came back from far away".
     */
    static Optional<CobbieEntity> cobbie(MinecraftServer server, int radius) {
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return Optional.empty();
        }
        ServerPlayer owner = owner(server);
        ServerLevel level = server.overworld();
        AABB box = new AABB(owner.blockPosition()).inflate(radius, 32, radius);
        List<Entity> found = level.getEntities((Entity) null, box, e -> e.getType() == CobbieEntities.COBBIE);
        return found.isEmpty() ? Optional.empty() : Optional.of((CobbieEntity) found.get(0));
    }

    static CobbieEntity spawn(ClientGameTestContext context, TestServerContext server) {
        awaitQuietBrain(server);
        command(server, "spawncobbie");
        try {
            server.waitFor(s -> cobbie(s).isPresent(), 400);
        } catch (Throwable t) {
            throw new AssertionError("/spawncobbie never produced a companion near the owner", t);
        }
        return server.computeOnServer(s -> cobbie(s).orElseThrow());
    }

    /** Wait for the connection probe to settle into "connected" (nameplate "§eCobbie"). */
    static void awaitBrain(TestServerContext server) {
        try {
            server.waitFor(s -> cobbie(s).map(c -> {
                String name = c.getCustomName() == null ? "" : c.getCustomName().getString();
                return name.endsWith("Cobbie");
            }).orElse(false), 600);
        } catch (Throwable t) {
            String name = server.computeOnServer(s -> cobbie(s)
                    .map(c -> c.getCustomName() == null ? "" : c.getCustomName().getString())
                    .orElse("no cobbie"));
            throw new AssertionError("Cobbie never reached a connected brain; nameplate was \"" + name + "\"", t);
        }
    }

    static void permit(TestServerContext server, String which, boolean on) {
        command(server, "cobbie permit " + which + " " + (on ? "on" : "off"));
    }

    /**
     * Wait until no model request is in flight anywhere in the process.
     *
     * <p>{@code CobbieBrain.inFlightCount()} is static and shared by every test class in one run, so
     * "he is not thinking" is only meaningful once the previous class's reply has landed — a request
     * started in the last world can still be running, and a fresh companion then looks like he is
     * thinking about a conversation from a world that no longer exists. Spawning waits on this, and so
     * does the status tag's precondition. A hosted reply takes seconds; a local model takes longer, so
     * the bound is generous on purpose. If it never drains, that is a stuck request, not a tag problem.
     */
    static void awaitQuietBrain(TestServerContext server) {
        try {
            server.waitFor(s -> CobbieBrain.inFlightCount() == 0, 600);
        } catch (Throwable t) {
            throw new AssertionError("A model request was still in flight after 600 ticks ("
                    + CobbieBrain.inFlightCount() + " outstanding); the counter never dropped back to zero, "
                    + "which is a stuck request and not a status-tag problem", t);
        }
    }

    /**
     * What a right-click on him does from a given sneaking state — the same call the server makes
     * when the client sends its interaction packet, so the shift gate is the real one.
     */
    static net.minecraft.world.InteractionResult rightClick(TestServerContext server, boolean sneaking) {
        return server.computeOnServer(s -> {
            ServerPlayer owner = owner(s);
            CobbieEntity cobbie = cobbie(s).orElseThrow();
            owner.setShiftKeyDown(sneaking);
            try {
                return owner.interactOn(cobbie, net.minecraft.world.InteractionHand.MAIN_HAND,
                        net.minecraft.world.phys.Vec3.atCenterOf(cobbie.blockPosition()));
            } finally {
                owner.setShiftKeyDown(false);
            }
        });
    }

    /**
     * Run a slash command as the owner and return everything it answered.
     *
     * <p>A command's reply is chat, which a test cannot read back — so the owner's own source stack is
     * used with only the sink swapped: permissions, entity and position are the real ones, and the text
     * lands in a string instead of in his chat window.
     */
    static String captureCommand(TestServerContext server, String command) {
        return server.computeOnServer(s -> {
            StringBuilder out = new StringBuilder();
            CommandSource sink = new CommandSource() {
                @Override
                public void sendSystemMessage(Component component) {
                    out.append(component.getString()).append(' ');
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            s.getCommands().performPrefixedCommand(owner(s).createCommandSourceStack().withSource(sink), command);
            return out.toString();
        });
    }

    /**
     * Wait until the plan in flight has finished.
     *
     * <p>Plan-shaped assertions go wrong in one way above all others: reading a value before the plan
     * that changes it has run. A test that queues a second job while the first is still retrying then
     * asserts against a job that has not started, and the failure reads like a product bug — it looked
     * exactly like that twice. Waiting on the plan itself, rather than on a side effect that may arrive
     * later, is the fix, and it belongs here so no new test has to rediscover it.
     */
    static void awaitPlanSettled(TestServerContext server, int ticks) {
        try {
            server.waitFor(s -> cobbie(s).map(c -> c.lastPlan() == null || c.lastPlan().isFinished())
                    .orElse(false), ticks);
        } catch (Throwable t) {
            throw new AssertionError("The plan never settled; it is "
                    + server.computeOnServer(s -> cobbie(s)
                    .map(c -> c.lastPlan() == null ? "none" : c.lastPlan().status().toString())
                    .orElse("no companion")), t);
        }
    }

    /**
     * A square to build in, where the builder stands, and the face a player standing there would click.
     *
     * @param square  the square the block has to appear in
     * @param feet    where the builder's feet are
     * @param clicked the block the builder clicks
     * @param face    the face of that block the builder clicks
     */
    record Spot(BlockPos square, BlockPos feet, BlockPos clicked, Direction face) {

        /** The same stance and the same click, moved sideways: the square an oracle is placed in. */
        Spot moved(int dx, int dz) {
            return new Spot(square.offset(dx, 0, dz), feet.offset(dx, 0, dz), clicked.offset(dx, 0, dz), face);
        }
    }

    /**
     * What the game gives <em>a real player</em>, standing at this spot, who clicks that face with that
     * item in hand.
     *
     * <p>This is the oracle every placement in these tests is measured against, and it is deliberately
     * the same call the server performs for a client with an item in hand — the owner's own
     * {@code gameMode.useItemOn}, with the stance and the click named by the test. Nothing of Cobbie's
     * placement path is on it, so a state that differs from this one is a state the game would not have
     * produced for a player standing in the same place.
     */
    static BlockState playerUseOnBlock(TestServerContext server, Spot where, Item item) {
        return server.computeOnServer(s -> {
            ServerPlayer owner = owner(s);
            ServerLevel level = s.overworld();
            Vec3 aim = Vec3.atCenterOf(where.square());
            double dx = aim.x - (where.feet().getX() + 0.5);
            double dy = aim.y - (where.feet().getY() + 1.62);
            double dz = aim.z - (where.feet().getZ() + 0.5);
            double flat = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            float pitch = (float) (-(Math.atan2(dy, flat) * 180.0 / Math.PI));
            owner.teleportTo(level, where.feet().getX() + 0.5, where.feet().getY(),
                    where.feet().getZ() + 0.5, Set.of(), yaw, pitch, true);
            ItemStack stack = new ItemStack(item, 1);
            owner.setItemInHand(InteractionHand.MAIN_HAND, stack);
            try {
                owner.gameMode.useItemOn(owner, level, stack, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(where.clicked()).relative(where.face(), 0.5),
                                where.face(), where.clicked(), false));
                return level.getBlockState(where.square());
            } finally {
                owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        });
    }

    /** Put him on a block, standing still, which is where a placement is made from. */
    static void stand(TestServerContext server, BlockPos feet) {
        server.computeOnServer(s -> {
            CobbieEntity cobbie = cobbie(s).orElseThrow();
            cobbie.getNavigation().stop();
            cobbie.teleportTo(s.overworld(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                    Set.of(), cobbie.getYRot(), 0.0f, true);
            cobbie.getNavigation().stop();
            return null;
        });
    }

    /** Ask him to put one of these blocks in this square; returns the plan's refusal, or null. */
    static String offerPlace(TestServerContext server, BlockPos square, Item item) {
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(net.minecraft.world.level.block.Block.byItem(item)).toString();
        return server.computeOnServer(s -> cobbie(s).orElseThrow()
                .offerPlan("place a block of " + blockId + " here",
                        List.of(new com.rishi.cobbie.action.ActionIntent(
                                com.rishi.cobbie.action.ActionVocabulary.PLACE, blockId,
                                square.getX(), square.getY(), square.getZ(), ""))));
    }

    /** A blockstate as the world holds it: the block's id and every property, for a failure message. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static String stateText(BlockState state) {
        StringBuilder sb = new StringBuilder(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var properties = state.getProperties();
        if (!properties.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (var property : properties) {
                if (!first) sb.append(',');
                first = false;
                sb.append(property.getName()).append('=')
                        .append(((net.minecraft.world.level.block.state.properties.Property) property)
                                .getName(state.getValue(property)));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** The last plan's report, for a message about a placement that did not happen. */
    static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> cobbie(s).map(CobbieEntity::lastPlan)
                .map(plan -> plan.status() + " :: " + plan.report()).orElse("no plan"));
    }

    /** Put a block in the world through the command system, so the arena is exactly what the test means. */
    static void stoneAt(TestServerContext server, BlockPos pos) {
        command(server, "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:stone");
    }

    /**
     * The whole claim of a placement, in one place: he builds it, the owner builds the same thing from
     * the identical stance on an identical square, and the two blockstates have to agree — plus the
     * properties the test names, so a failure points at {@code half} or {@code facing} rather than at
     * "the states differ".
     *
     * @param blockId the block that must end up in the square
     * @param named   properties that must read this way; empty means the block carries none
     */
    static void placedLikeAPlayer(TestServerContext server, String what, Item item, Spot subject, Spot oracle,
            String blockId, Map<String, String> named) {
        BlockState byPlayer = playerUseOnBlock(server, oracle, item);
        if (byPlayer.isAir()) {
            throw new AssertionError(what + ": the owner's own right-click placed nothing at "
                    + oracle.square() + " either, so this case cannot be measured");
        }
        server.computeOnServer(s -> {
            cobbie(s).orElseThrow().addItem(new ItemStack(item, 1));
            return null;
        });
        stand(server, subject.feet());
        String refusal = offerPlace(server, subject.square(), item);
        if (refusal != null) throw new AssertionError(what + ": the plan was refused — " + refusal);
        // Wait for the square itself, not only for the plan: a finished plan from the previous case
        // would satisfy "settled" before this one has started.
        try {
            server.waitFor(s -> !s.overworld().getBlockState(subject.square()).isAir(), 400);
        } catch (Throwable ignored) {
            // the assertion below says what the square holds instead, with the plan's report
        }
        awaitPlanSettled(server, 400);

        BlockState placed = server.computeOnServer(s -> s.overworld().getBlockState(subject.square()));
        if (placed.isAir()) {
            throw new AssertionError(what + ": nothing was placed at " + subject.square() + ". "
                    + lastReport(server));
        }
        if (!placed.equals(byPlayer)) {
            throw new AssertionError(what + ": a player standing where he stood gets "
                    + stateText(byPlayer) + ", and he built " + stateText(placed) + ". "
                    + lastReport(server));
        }
        String text = server.computeOnServer(s -> stateText(s.overworld().getBlockState(subject.square())));
        if (!text.startsWith(blockId)) {
            throw new AssertionError(what + ": " + subject.square() + " holds " + text + " instead of "
                    + blockId);
        }
        if (named.isEmpty() && text.contains("[")) {
            throw new AssertionError(what + ": this block should carry no properties, and reads " + text);
        }
        for (Map.Entry<String, String> property : new LinkedHashMap<>(named).entrySet()) {
            String want = property.getKey() + "=" + property.getValue();
            if (!text.contains(want)) {
                throw new AssertionError(what + ": " + subject.square() + " should read " + want
                        + " and reads " + text);
            }
        }
    }

    /**
     * Let this many ticks elapse. A predicate like {@code tick % 20 == 0} matches on the <b>first</b>
     * tick divisible by twenty, so it waits nothing — two negatives in this suite passed in a second
     * while claiming twenty and forty-five. Time is measured, not requested.
     */
    static int elapsed(TestServerContext server, int ticks) {
        int start = server.computeOnServer(MinecraftServer::getTickCount);
        server.waitFor(s -> s.getTickCount() - start >= ticks, ticks + 200);
        return server.computeOnServer(MinecraftServer::getTickCount) - start;
    }

    /** Teleport the owner properly: a server-side snap is undone by the client's next position packet. */
    static void teleportOwner(TestServerContext server, double dx, double dy, double dz) {
        server.computeOnServer(s -> {
            ServerPlayer owner = owner(s);
            owner.teleportTo(s.overworld(), owner.getX() + dx, owner.getY() + dy, owner.getZ() + dz,
                    Set.of(), owner.getYRot(), owner.getXRot(), true);
            return null;
        });
    }
}
