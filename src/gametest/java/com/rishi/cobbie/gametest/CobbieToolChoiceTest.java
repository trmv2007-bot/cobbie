package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which tool he actually raises, and what it costs him.
 *
 * <p>This is the slice's evidence, and every assertion in it is read off the <b>inventory after the run</b> —
 * which stack is in his hand, which damage value each tool carries — rather than off what he said. A sentence
 * about mining stone with the stone pickaxe is not evidence that the diamond one is still whole; the damage
 * numbers are.
 *
 * <p>Three things a player notices over a session:
 *
 * <ul>
 *   <li><b>The cheapest tool that will do the job</b> — stone, iron and diamond pickaxes on one block of stone,
 *       and the stone one has to be the one that wears.</li>
 *   <li><b>A nearly-spent tool is not the one he starts a job with</b> — a diamond pickaxe on its last use and a
 *       fresh iron one, two blocks of stone: the iron one does the work, the diamond one is untouched, and he
 *       says why once rather than twice.</li>
 *   <li><b>No tool of a sufficient tier is a refusal</b>, not a bare-handed swing — a wooden pickaxe on obsidian
 *       used to break the obsidian and report "nothing dropped", which is the block lost for nothing.</li>
 * </ul>
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=ToolChoice --no-build-cache --rerun-tasks}.
 */
public final class CobbieToolChoiceTest implements FabricClientGameTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED");

    @Override
    public void runTest(ClientGameTestContext context) {
        // Survival on purpose: "no tool of a sufficient tier" is only a refusal where a drop matters, and a
        // creative owner may take a block apart with his hands because the drop is not the point.
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            // Assistive, and only the verbs this class drives: his own initiative projects would be a second
            // plan inside the window each case measures.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collection", true);
            awaitIdle(server, 400);

            theCheapestToolThatWillDoIt(server);
            theNearlySpentToolIsNotTheOneHeStartsWith(server);
            noToolOfTheRightSortIsARefusal(server);

            System.out.println("[toolchoice] three cases: the stone pickaxe wore on stone, the diamond one was"
                    + " left alone and said so, and obsidian without a diamond pickaxe was refused rather"
                    + " than broken for nothing");
        }
    }

    // ==================================================================
    // 1. the cheapest tool that will do the job
    // ==================================================================

    private void theCheapestToolThatWillDoIt(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos stone = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            cobbie.addItem(new ItemStack(Items.STONE_PICKAXE, 1));
            cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
            cobbie.addItem(new ItemStack(Items.DIAMOND_PICKAXE, 1));
            return squareBeside(s, cobbie, "minecraft:stone");
        });
        server.computeOnServer(s -> {
            CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("mine that stone",
                    List.of(new ActionIntent(ActionVocabulary.MINE, "minecraft:stone",
                            stone.getX(), stone.getY(), stone.getZ(), "")));
            return null;
        });
        TaskPlan plan = settledPlan(server, before, "mine that stone", 600,
                "A mine of one stone block never settled");

        require(plan.status() == TaskPlan.Status.COMPLETED, "the mine has to finish: " + plan.report());
        require(server.computeOnServer(s -> s.overworld().getBlockState(stone).isAir()),
                "FALSE SUCCESS: the plan completed and the stone is still standing — " + plan.report());

        Wear wear = wearOf(server);
        require(wear.holds(Items.STONE_PICKAXE),
                "FALSE CHOICE: stone, iron and diamond pickaxes, one block of stone, and his hand holds "
                        + wear.held() + " — " + plan.report());
        require(wear.damage(Items.STONE_PICKAXE) == 1,
                "the stone pickaxe has to carry the wear of the block: it reads "
                        + wear.damage(Items.STONE_PICKAXE) + " — " + plan.report());
        require(wear.damage(Items.IRON_PICKAXE) == 0 && wear.damage(Items.DIAMOND_PICKAXE) == 0,
                "FALSE CHOICE: the finer tools were worn on cobblestone — iron " + wear.damage(Items.IRON_PICKAXE)
                        + ", diamond " + wear.damage(Items.DIAMOND_PICKAXE) + " — " + plan.report());
        System.out.println("[toolchoice] cheapest: hand " + wear.held() + ", wear stone=1 iron=0 diamond=0");
    }

    // ==================================================================
    // 2. a nearly-spent tool is not the one he starts a job with
    // ==================================================================

    private void theNearlySpentToolIsNotTheOneHeStartsWith(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos[] blocks = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            ItemStack spent = new ItemStack(Items.DIAMOND_PICKAXE, 1);
            // One use left: vanilla destroys a stack whose damage reaches its maximum, so this pickaxe cannot
            // see a single block through without breaking.
            spent.setDamageValue(spent.getMaxDamage() - 1);
            cobbie.addItem(spent);
            cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
            BlockPos first = squareBeside(s, cobbie, "minecraft:stone");
            BlockPos second = first.offset(0, 0, 1);
            CobbieTestSupport.command(s, "setblock " + second.getX() + " " + second.getY() + " " + second.getZ()
                    + " minecraft:stone");
            return new BlockPos[] {first, second};
        });
        server.computeOnServer(s -> {
            CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("mine these two stones", List.of(
                    new ActionIntent(ActionVocabulary.MINE, "minecraft:stone",
                            blocks[0].getX(), blocks[0].getY(), blocks[0].getZ(), ""),
                    new ActionIntent(ActionVocabulary.MINE, "minecraft:stone",
                            blocks[1].getX(), blocks[1].getY(), blocks[1].getZ(), "")));
            return null;
        });
        TaskPlan plan = settledPlan(server, before, "mine these two stones", 900,
                "A two-block mine never settled");

        require(plan.status() == TaskPlan.Status.COMPLETED, "both blocks have to come out: " + plan.report());
        require(server.computeOnServer(s -> s.overworld().getBlockState(blocks[0]).isAir()
                        && s.overworld().getBlockState(blocks[1]).isAir()),
                "FALSE SUCCESS: the plan completed and a block is still standing — " + plan.report());

        Wear wear = wearOf(server);
        require(wear.holds(Items.IRON_PICKAXE),
                "FALSE CHOICE: a diamond pickaxe on its last use beside a fresh iron one, and his hand holds "
                        + wear.held() + " — " + plan.report());
        require(wear.damage(Items.DIAMOND_PICKAXE) == wear.max(Items.DIAMOND_PICKAXE) - 1,
                "FALSE CHOICE: the nearly spent diamond pickaxe was worn, damage "
                        + wear.damage(Items.DIAMOND_PICKAXE) + " of " + wear.max(Items.DIAMOND_PICKAXE)
                        + " — " + plan.report());
        require(wear.damage(Items.IRON_PICKAXE) == 2,
                "the iron pickaxe did two blocks of work and reads " + wear.damage(Items.IRON_PICKAXE)
                        + " — " + plan.report());

        // Said once, not once a block — and it has to name both tools for the owner to know what changed.
        int said = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            int times = 0;
            for (String line : cobbie.recentLines()) {
                if (line.toLowerCase(Locale.ROOT).contains("nearly spent")) times++;
            }
            return times;
        });
        String lines = server.computeOnServer(s -> String.join(" | ",
                CobbieTestSupport.cobbie(s).orElseThrow().recentLines()));
        require(said == 1, "he has to say which tool he took and why, exactly once; he said it " + said
                + " times in: " + lines);
        require(lines.toLowerCase(Locale.ROOT).contains("iron") && lines.toLowerCase(Locale.ROOT).contains("diamond"),
                "the line has to name the tool he took and the one he left alone: " + lines);
        System.out.println("[toolchoice] wear: hand " + wear.held() + ", wear iron=2 diamond untouched, said \""
                + lines + "\"");
    }

    // ==================================================================
    // 3. no tool of a sufficient tier is a refusal
    // ==================================================================

    private void noToolOfTheRightSortIsARefusal(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos obsidian = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            // A pickaxe, and the wrong one: the game will not pay out on obsidian from wood, so a swing is the
            // block lost for nothing. Before 0.81.0 he took it and reported "nothing dropped".
            cobbie.addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
            return squareBeside(s, cobbie, "minecraft:obsidian");
        });
        server.computeOnServer(s -> {
            CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("mine that obsidian",
                    List.of(new ActionIntent(ActionVocabulary.MINE, "minecraft:obsidian",
                            obsidian.getX(), obsidian.getY(), obsidian.getZ(), "")));
            return null;
        });
        TaskPlan plan = settledPlan(server, before, "mine that obsidian", 400,
                "A mine of obsidian with a wooden pickaxe never settled");

        require(plan.status() == TaskPlan.Status.FAILED,
                "a wooden pickaxe cannot have obsidian, so the job has to be refused rather than reported done: "
                        + plan.report());
        require(server.computeOnServer(s -> s.overworld().getBlockState(obsidian).is(Blocks.OBSIDIAN)),
                "FALSE REFUSAL: the obsidian is gone, so he broke a block the game would not have paid out for — "
                        + plan.report());
        // The reason lives on the failed step, which is where the narrator reads it from too.
        StringBuilder why = new StringBuilder(plan.failureReason() == null ? "" : plan.failureReason());
        for (com.rishi.cobbie.task.TaskStep step : plan.steps()) {
            if (step.status() == com.rishi.cobbie.task.TaskStep.Status.FAILED) why.append(' ').append(step.result());
        }
        String reason = why.toString().toLowerCase(Locale.ROOT);
        require(reason.contains("proper tool"),
                "the refusal has to name what he is missing, and it reads \"" + why + "\" — " + plan.report());
        Wear wear = wearOf(server);
        require(wear.damage(Items.WOODEN_PICKAXE) == 0,
                "a refused job must not wear the tool he was honest enough not to use: it reads "
                        + wear.damage(Items.WOODEN_PICKAXE));
        System.out.println("[toolchoice] refusal: \"" + why + "\", obsidian still standing, wooden pickaxe unworn");
    }

    // ==================================================================
    // Reading the bag afterwards
    // ==================================================================

    /** What the inventory says after a run: which stack is in his hand, and the damage each tool carries. */
    private record Wear(String hand, Map<String, Integer> damage, Map<String, Integer> maxima) {
        boolean holds(net.minecraft.world.item.Item item) {
            return hand.equals(nameOf(item));
        }

        int damage(net.minecraft.world.item.Item item) {
            return damage.getOrDefault(nameOf(item), 0);
        }

        int max(net.minecraft.world.item.Item item) {
            return maxima.getOrDefault(nameOf(item), 0);
        }

        String held() {
            return hand;
        }
    }

    private static Wear wearOf(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            Map<String, Integer> damage = new java.util.HashMap<>();
            Map<String, Integer> maxima = new java.util.HashMap<>();
            var inventory = cobbie.getInventoryContainer();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                record(inventory.getItem(slot), damage, maxima);
            }
            record(cobbie.getMainHandItem(), damage, maxima);
            return new Wear(nameOf(cobbie.getMainHandItem()), damage, maxima);
        });
    }

    private static void record(ItemStack stack, Map<String, Integer> damage, Map<String, Integer> maxima) {
        if (stack == null || stack.isEmpty()) return;
        String name = nameOf(stack);
        damage.put(name, stack.getDamageValue());
        maxima.put(name, stack.getMaxDamage());
    }

    /** "iron_pickaxe": the registry path, which is what a stack can be counted by. */
    private static String nameOf(net.minecraft.world.item.Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    private static String nameOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : nameOf(stack.getItem());
    }

    // ==================================================================
    // Test plumbing
    // ==================================================================

    /** A solid block next to him, on ground, close enough to reach without a walk. */
    private static BlockPos squareBeside(net.minecraft.server.MinecraftServer server, CobbieEntity cobbie,
                                         String block) {
        BlockPos at = cobbie.blockPosition();
        BlockPos[] candidates = {at.east(), at.west(), at.north(), at.south()};
        for (BlockPos spot : candidates) {
            if (!server.overworld().getBlockState(spot).isAir()) continue;
            if (server.overworld().getBlockState(spot.above()).isAir()) {
                CobbieTestSupport.command(server, "setblock " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                        + " " + block);
                return spot;
            }
        }
        throw new AssertionError("nowhere beside the companion at " + at.toShortString() + " to put a block");
    }

    private static void emptyBag(CobbieEntity cobbie) {
        var inventory = cobbie.getInventoryContainer();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        cobbie.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static long latestId(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
    }

    private static void awaitIdle(TestServerContext server, int ticks) {
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> !c.isBusy()).orElse(false), ticks);
        } catch (Throwable t) {
            throw new AssertionError("He never went idle between cases; he is busy with "
                    + server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(CobbieEntity::currentTaskDescription).orElse("nothing")), t);
        }
    }

    private static long findPlan(CobbieEntity cobbie, long since, String prefix) {
        for (long id = cobbie.latestPlanId(); id > since; id--) {
            String record = cobbie.planRecord(id);
            if (record != null && record.startsWith(prefix)) return id;
        }
        return -1;
    }

    private static TaskPlan settledPlan(TestServerContext server, long since, String prefix, int ticks,
                                        String why) {
        long id;
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> findPlan(c, since, prefix) >= 0).orElse(false), 200);
            id = server.computeOnServer(s -> findPlan(CobbieTestSupport.cobbie(s).orElseThrow(), since, prefix));
            server.waitFor(s -> {
                String record = CobbieTestSupport.cobbie(s).map(c -> c.planRecord(id)).orElse(null);
                return record != null && TERMINAL.contains(statusIn(record));
            }, ticks);
        } catch (Throwable t) {
            throw new AssertionError(why + "; plans after " + since + " read: " + describe(server, since), t);
        }
        TaskPlan plan = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().planAfter(id - 1));
        if (plan == null || plan.id() != id) {
            throw new AssertionError("Plan " + id + " is no longer remembered, so nothing can be said about it");
        }
        return plan;
    }

    private static String statusIn(String record) {
        int mark = record.indexOf(" -> ");
        if (mark < 0) return "";
        int end = record.indexOf(" — ", mark + 4);
        return record.substring(mark + 4, end < 0 ? record.length() : end);
    }

    private static String describe(TestServerContext server, long since) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElse(null);
            if (cobbie == null) return "no companion";
            StringBuilder sb = new StringBuilder();
            for (long id = since + 1; id <= cobbie.latestPlanId(); id++) {
                String record = cobbie.planRecord(id);
                if (record != null) sb.append(record).append(" | ");
            }
            return sb.length() == 0 ? "no plans after " + since : sb.toString();
        });
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
