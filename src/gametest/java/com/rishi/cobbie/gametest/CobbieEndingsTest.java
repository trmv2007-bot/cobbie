package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskStep;
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
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A verb is a promise, and this class asks every promise whether it was kept.
 *
 * <p>Every other class in this suite proves a verb works in a world built to agree with it. The question
 * here is the other one: when the world refuses, what does he say? One deliberate failure per verb, and
 * exactly three endings are allowed — <b>it succeeded anyway</b> (and the world shows the work),
 * <b>it recovered once and succeeded</b> (proven elsewhere: {@code CobbieStalePlanTest} case 3), or
 * <b>it stopped and named what stopped it</b>. The fourth ending, <b>success over work the world does not
 * have</b>, is the one this slice exists to remove, and it is the only one that fails loudly here.
 *
 * <p>The cases are grouped by the shape of the refusal rather than by verb, because the shapes are what
 * matter: a station he does not have, an item he does not carry, a target the world no longer contains,
 * an instruction the recipe table cannot satisfy at all. Each group still prints one line per verb, so a
 * regression names the verb instead of the group.
 *
 * <p>After the three tables come the cases that are not refusals at all, because that is where the
 * interesting endings live: an instruction the recipe table cannot satisfy, <b>refused before a plan
 * exists</b>; an objective the world already holds, which is a completion about work nobody did and must
 * be said as one; a <b>write</b> to a sign he has to walk to, which until 0.79.0 reported <i>arrived</i>
 * as a success and wrote nothing; the same write with the sign broken on the way, which must stop naming
 * the sign; a verb that cannot work, which must be tried a <b>counted</b> number of times and then stop
 * rather than retrying for ever; and <b>farm</b>, where the number he reports has to be the number of
 * crops that actually left the world.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Endings}.
 */
public final class CobbieEndingsTest implements FabricClientGameTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED");
    /** Far enough that a step has to walk, short enough that the walk finishes inside the class. */
    private static final int AWAY = 8;

    /**
     * One induced failure: the step, the phrase that proves he named the cause, and how a person would
     * say it in the log line.
     */
    private record Case(ActionIntent intent, String cause, String what) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            // Assistive, not creative: his own initiative projects would be a second plan in the window and
            // every refusal here would then be judged against somebody else's job.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            CobbieTestSupport.permit(server, "explore", false);
            CobbieTestSupport.permit(server, "collection", true);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "crafting", true);
            CobbieTestSupport.permit(server, "containers", true);
            CobbieTestSupport.permit(server, "combat", true);
            awaitIdle(server, 400);

            // ---- A. A station he does not have. Seven verbs, one shape. ----
            // Each of these is asked with everything switched on and nothing built: the only thing missing
            // is the station itself, so the reason must be the station and not a switch or an item.
            refusals(server, "the station he has not got", List.of(
                    new Case(new ActionIntent(ActionVocabulary.ENCHANT, "minecraft:diamond_sword", 0, 0, 0, ""),
                            "enchanting table", "enchant with no enchanting table"),
                    new Case(new ActionIntent(ActionVocabulary.ANVIL, "minecraft:diamond_sword", 0, 0, 0, ""),
                            "anvil", "anvil with no anvil"),
                    new Case(new ActionIntent(ActionVocabulary.SMITH, "minecraft:diamond_pickaxe", 0, 0, 0, ""),
                            "smithing table", "smith with no smithing table"),
                    new Case(new ActionIntent(ActionVocabulary.GRIND, "minecraft:diamond_pickaxe", 0, 0, 0, ""),
                            "grindstone", "grind with no grindstone"),
                    new Case(new ActionIntent(ActionVocabulary.WEAVE, "minecraft:creeper_banner_pattern", 0, 0, 0, ""),
                            "loom", "weave with no loom"),
                    new Case(new ActionIntent(ActionVocabulary.MAP, "minecraft:map", 0, 0, 0, ""),
                            "cartography table", "map with no cartography table"),
                    new Case(new ActionIntent(ActionVocabulary.BREW, "minecraft:nether_wart", 0, 0, 0, ""),
                            "brewing stand", "brew with no brewing stand")));

            // ---- B. An item he does not carry. ----
            // The mirror of A: the station may be anywhere or nowhere, but the thing he was asked to spend
            // is not in his bag. Each must say what it has not got, and none may spend anything.
            // He is given one stick first: with a genuinely empty bag the resolver can only answer "I am
            // not carrying anything", and a refusal that cannot name the thing it is missing is the shrug
            // this group is here to forbid.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                emptyBag(cobbie);
                cobbie.addItem(new ItemStack(Items.STICK, 1));
                return null;
            });
            refusals(server, "the item he is not carrying", List.of(
                    new Case(new ActionIntent(ActionVocabulary.DROP, "minecraft:diamond", 0, 0, 0, ""),
                            "diamond", "drop a diamond he has not got"),
                    new Case(new ActionIntent(ActionVocabulary.GIVE, "minecraft:diamond", 0, 0, 0, ""),
                            "diamond", "give a diamond he has not got"),
                    new Case(new ActionIntent(ActionVocabulary.EQUIP, "minecraft:diamond_pickaxe", 0, 0, 0, ""),
                            "diamond_pickaxe", "equip a pickaxe he has not got"),
                    new Case(new ActionIntent(ActionVocabulary.DYE, "purple", 0, 0, 0, ""),
                            "purple", "dye with no purple dye"),
                    new Case(new ActionIntent(ActionVocabulary.REPAIR, "", 0, 0, 0, ""),
                            "two", "mend with only one of a thing"),
                    new Case(new ActionIntent(ActionVocabulary.USE, "minecraft:shears", 0, 0, 0, ""),
                            "shears", "use shears he has not got")));

            // ---- C. A target the world does not contain. ----
            // The verb is allowed, the item may even be carried, and the thing it should happen to is not
            // there. This is the group a false success hides in, because there is nothing to notice.
            BlockPos nothing = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .blockPosition().offset(2, 0, 2));
            refusals(server, "the thing that is not there", List.of(
                    // His spoken line has its coordinates scrubbed and the empty preposition with them:
                    // "there is nothing to take from 3 4 5" is heard as "there is nothing to take".
                    new Case(new ActionIntent(ActionVocabulary.TAKE, "minecraft:coal",
                            nothing.getX(), nothing.getY(), nothing.getZ(), ""),
                            "nothing to take", "take from an empty square"),
                    new Case(new ActionIntent(ActionVocabulary.PUT, "minecraft:coal",
                            nothing.getX(), nothing.getY(), nothing.getZ(), ""),
                            "nothing to put things", "put into an empty square"),
                    new Case(new ActionIntent(ActionVocabulary.BOARD, "boat", 0, 0, 0, ""),
                            "no boat or minecart", "board a boat that is not there"),
                    new Case(new ActionIntent(ActionVocabulary.TRADE, "minecraft:bread", 0, 0, 0, "1"),
                            "no villager", "trade with no villager"),
                    new Case(new ActionIntent(ActionVocabulary.BREED, "cow", 0, 0, 0, ""),
                            "cow", "breed with no cows"),
                    new Case(new ActionIntent(ActionVocabulary.COLLECT, "", 0, 0, 0, ""),
                            "nothing to pick up", "collect at an empty square"),
                    new Case(new ActionIntent(ActionVocabulary.FARM, "wheat", 0, 0, 0, ""),
                            "no crops", "farm with no crops"),
                    new Case(new ActionIntent(ActionVocabulary.ATTACK, "", 0, 0, 0, ""),
                            "nothing i am allowed to fight", "attack with nothing to fight"),
                    new Case(new ActionIntent(ActionVocabulary.SMELT, "minecraft:raw_iron",
                            nothing.getX(), nothing.getY(), nothing.getZ(), "1"),
                            "no furnace", "smelt at a square that is not a furnace")));

            // ---- D. An instruction the recipe table cannot satisfy is refused before a plan exists. ----
            // The refusal is not an ending at all: nothing is handed to the runner, so there is no project
            // to fail and no promise in the chat about work nobody could start.
            refusedBeforePlan(server);

            // ---- D2. An objective that already holds is not work, and it is not a failure either. ----
            // The one ending that is neither the world's refusal nor his own doing: the square he was sent
            // to clear is already clear, so the honest answer is that there was nothing there to break.
            alreadyThere(server);

            // ---- E. A write to a sign he has to walk to. ----
            // The defect this slice fixes. Before: the walk arrived, there was no case for the write in the
            // hand-over, and the default arm said "arrived" — a completed plan over a blank sign.
            writeAtRange(server);

            // ---- F. The same write, with the sign broken on the way. ----
            writeWithTheSignGone(server);

            // ---- G. A verb that cannot work is tried a counted number of times, then it stops. ----
            countedRetries(server);

            // ---- H. Farm, where the count he reports has to be the number of crops that left the world. ----
            farmCountsWhatItHarvested(server);

            System.out.println("[endings] done: " + driven.size() + " verbs driven to failure ("
                    + String.join(", ", driven) + "), every ending either the world's work or a reason"
                    + " naming what stopped it");
        }
    }

    // ==================================================================
    // The refusal table: one induced failure, one sentence, no false success
    // ==================================================================

    private void refusals(TestServerContext server, String group, List<Case> cases) {
        for (Case one : cases) {
            awaitIdle(server, 60);
            long before = latestId(server);
            String refused = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan(one.what(), List.of(one.intent())));
            if (refused != null) {
                // A refusal at the door is one of the honest endings: he would not start work he cannot do.
                // It still has to name the cause, or it is a shrug.
                require(refused.toLowerCase(Locale.ROOT).contains(one.cause()),
                        group + ": \"" + one.what() + "\" was refused with a reason that does not name it: \""
                                + refused + "\"");
                System.out.println("[endings] " + one.what() + ": refused before starting — \"" + refused + "\"");
                continue;
            }
            TaskPlan plan = settledPlan(server, before, one.what(), 200,
                    group + ": \"" + one.what() + "\" never settled");
            // Nothing in this group can have been achieved: there is no station, no item or no target for
            // the verb to work on. So the world's answer is "no" and any COMPLETED is a false success.
            ending(server, plan, one.what(), one.cause(), () -> false);
        }
    }

    private void refusedBeforePlan(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        // A bed needs wool and planks; with neither a single log's worth of either, the chain has no bottom.
        String refused = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            cobbie.getInventoryContainer().setItem(0,
                    new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.Identifier.parse("minecraft:white_wool"))
                            .orElseThrow().value(), 3));
            return cobbie.offerPlan("make me a bed", List.of(
                    new ActionIntent(ActionVocabulary.CRAFT, "minecraft:white_bed", 0, 0, 0, "1")));
        });
        require(refused != null,
                "A craft with no planks and no logs was accepted instead of refused");
        int plans = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().plansSince(before));
        require(plans == 0, "\"" + refused + "\" was refused and still " + plans + " plan(s) appeared");
        require(refused.toLowerCase(Locale.ROOT).contains("log"),
                "the refusal must name the bottom of the chain he can go and get, and it says \""
                        + refused + "\"");
        System.out.println("[endings] an unsatisfiable craft: refused, no plan — \"" + refused + "\"");
        driven.add(ActionVocabulary.CRAFT);
    }

    private void alreadyThere(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos empty = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos at = cobbie.blockPosition().offset(-2, 0, 0);
            CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                    + " minecraft:air");
            cobbie.offerPlan("break the stone that is not there", List.of(new ActionIntent(
                    ActionVocabulary.MINE, "minecraft:stone", at.getX(), at.getY(), at.getZ(), "")));
            return at;
        });
        TaskPlan plan = settledPlan(server, before, "break the stone that is not there", 200,
                "A mine of an empty square never settled");
        // The world's own answer, read independently: the square is air, and it was air before he started.
        ending(server, plan, "mine a square that is already empty", "nothing left", () ->
                server.computeOnServer(s -> s.overworld().getBlockState(empty).isAir()));
        require(!plan.log().toLowerCase(Locale.ROOT).contains("mined minecraft:stone"),
                "He did not break anything and the log says he did: " + plan.log());
    }

    // ==================================================================
    // The write: arriving is not writing
    // ==================================================================

    private void writeAtRange(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos sign = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos at = cobbie.blockPosition().offset(AWAY, 0, 0);
            CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                    + " minecraft:oak_sign[rotation=8]");
            cobbie.offerPlan("write on that sign over there", List.of(new ActionIntent(
                    ActionVocabulary.WRITE, "coal for sale", at.getX(), at.getY(), at.getZ(), "")));
            return at;
        });
        TaskPlan plan = settledPlan(server, before, "write on that sign over there", 600,
                "A write to a walkable sign never settled");
        // The independent check, and the whole point of the case: the words are on the sign or they are not.
        ending(server, plan, "write to a sign he walked to", "sign", () -> signReads(server, sign, "coal for sale"));
        require(signReads(server, sign, "coal for sale"),
                "The plan ended without the words on the sign: " + plan.report()
                        + "; sign reads " + server.computeOnServer(s -> signText(s, sign)));
    }

    private void writeWithTheSignGone(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos spot = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos at = cobbie.blockPosition().offset(0, 0, -AWAY);
            CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                    + " minecraft:oak_sign[rotation=0]");
            cobbie.offerPlan("write on that other sign", List.of(new ActionIntent(
                    ActionVocabulary.WRITE, "beware the dog", at.getX(), at.getY(), at.getZ(), "")));
            return at;
        });
        // He has to be on his way, or the sign is gone before the step ever stands up and this tests the
        // refusal at the door rather than the hand-over.
        walkedAwayFrom(server, before);
        server.computeOnServer(s -> {
            CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                    + " minecraft:air");
            return null;
        });
        TaskPlan plan = settledPlan(server, before, "write on that other sign", 600,
                "A write whose sign was broken on the way never settled");
        ending(server, plan, "write to a sign broken mid-walk", "sign", () -> false);
        require(server.computeOnServer(s -> s.overworld().getBlockState(spot).isAir()),
                "the fixture did not remove the sign, so nothing was induced");
    }

    // ==================================================================
    // Bounded retries: the count, not just the ending
    // ==================================================================

    private void countedRetries(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        BlockPos spot = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos at = cobbie.blockPosition().offset(2, 0, 0);
            emptyBag(cobbie);
            // A square he could build on and no dirt to build with: every attempt fails the same way, for
            // ever, unless the budget is a number.
            cobbie.offerPlan("put a block of dirt there", List.of(new ActionIntent(
                    ActionVocabulary.PLACE, "minecraft:dirt", at.getX(), at.getY(), at.getZ(), "")));
            return at;
        });
        TaskPlan plan = settledPlan(server, before, "put a block of dirt there", 600,
                "A place with nothing to place never settled — that is the loop");
        ending(server, plan, "a step that fails every time", "dirt", () -> false);
        TaskStep step = plan.steps().get(0);
        int allowed = plan.maxRetriesPerStep() + 1;
        require(step.attempts() <= allowed,
                "The step was tried " + step.attempts() + " times against a budget of " + allowed
                        + " — a retry loop without a count (" + plan.report() + ")");
        require(step.attempts() == allowed,
                "The budget is a number he spends, so a step that always fails should be tried exactly "
                        + allowed + " times; it was tried " + step.attempts() + " (" + plan.report() + ")");
        require(plan.totalRetries() == plan.maxRetriesPerStep(),
                "The plan counted " + plan.totalRetries() + " retries against a budget of "
                        + plan.maxRetriesPerStep());
        require(server.computeOnServer(s -> s.overworld().getBlockState(spot).isAir()),
                "The square has something in it after a place he had no material for");
        System.out.println("[endings] a step that fails every time: tried " + step.attempts()
                + " time(s) against a budget of " + allowed + ", then " + plan.status());
    }

    // ==================================================================
    // Farm: the number he says is the number of crops that left the world
    // ==================================================================

    private void farmCountsWhatItHarvested(TestServerContext server) {
        awaitIdle(server, 60);
        // First the refusal: nothing ripe is a report, not a job.
        long before = latestId(server);
        List<BlockPos> unripe = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            List<BlockPos> cells = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                BlockPos at = cobbie.blockPosition().offset(1 + i, -1, 1);
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " minecraft:farmland");
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + (at.getY() + 1) + " " + at.getZ()
                        + " minecraft:wheat[age=1]");
                cells.add(at.above());
            }
            cobbie.offerPlan("do the farm", List.of(new ActionIntent(
                    ActionVocabulary.FARM, "wheat", 0, 0, 0, "")));
            return cells;
        });
        TaskPlan nothingRipe = settledPlan(server, before, "do the farm", 200,
                "A farm with nothing ripe never settled");
        ending(server, nothingRipe, "farm with nothing ripe", "ripe", () -> false);
        require(server.computeOnServer(s -> unripe.stream()
                        .allMatch(p -> s.overworld().getBlockState(p).getBlock() == Blocks.WHEAT)),
                "wheat that was not ripe was taken anyway");

        // Then the count: two ripe crops, no seeds to replant, so what he says is exactly what he did.
        awaitIdle(server, 60);
        long second = latestId(server);
        List<BlockPos> ripe = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            List<BlockPos> cells = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                BlockPos at = cobbie.blockPosition().offset(1 + i, -1, 1);
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " minecraft:farmland");
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + (at.getY() + 1) + " " + at.getZ()
                        + " minecraft:wheat[age=7]");
                cells.add(at.above());
            }
            cobbie.offerPlan("harvest the wheat", List.of(new ActionIntent(
                    ActionVocabulary.FARM, "wheat", 0, 0, 0, "")));
            return cells;
        });
        TaskPlan harvested = settledPlan(server, second, "harvest the wheat", 400,
                "A farm with two ripe crops never settled");
        int leftStanding = server.computeOnServer(s -> (int) ripe.stream()
                .filter(p -> s.overworld().getBlockState(p).getBlock() == Blocks.WHEAT).count());
        require(leftStanding == 0, leftStanding + " ripe crop(s) are still standing after a harvest"
                + " that was reported: " + harvested.report());
        require(harvested.status() == TaskPlan.Status.COMPLETED,
                "Two ripe crops he had no seeds to replant are still a harvest, so this must complete: "
                        + harvested.report());
        require(harvested.log().contains("harvested 2"),
                "He harvested two crops and does not say so: " + harvested.report());
        require(harvested.log().contains("no seed"),
                "He had no seed to replant with and did not say that: " + harvested.report());
        System.out.println("[endings] farm: " + harvested.status() + " — " + harvested.log());
    }


    // ==================================================================
    // The rule being asserted, in one place
    // ==================================================================

    /**
     * The four endings this suite allows, and the one it forbids.
     *
     * <p>{@code worldShowsTheWork} is asked of the <b>world</b>, never of the outcome's own summary: a
     * handler and a test that read the same return value can only ever agree with each other, which is
     * exactly how a false success survives a green run.
     */
    private void ending(TestServerContext server, TaskPlan plan, String what, String cause,
                        java.util.function.BooleanSupplier worldShowsTheWork) {
        if (!plan.isFinished()) {
            throw new AssertionError(what + " never settled — that is the loop: " + plan.report());
        }
        String recorded = (plan.report() + " " + plan.abortReason() + " " + plan.log())
                .toLowerCase(Locale.ROOT);
        if (plan.status() == TaskPlan.Status.COMPLETED) {
            if (!worldShowsTheWork.getAsBoolean()) {
                throw new AssertionError("FALSE SUCCESS: " + what + " reported COMPLETED and the world"
                        + " disagrees — " + plan.report());
            }
            System.out.println("[endings] " + what + ": completed, and the world shows the work");
            driven.add(plan.steps().get(0).intent().action());
            return;
        }
        if (plan.status() == TaskPlan.Status.EXPIRED) {
            throw new AssertionError(what + " ran out of time rather than stopping with a reason: "
                    + plan.report());
        }
        require(recorded.contains(cause.toLowerCase(Locale.ROOT)),
                what + " stopped without naming " + cause + ": " + plan.report()
                        + " | said " + said(server));
        int attempts = plan.steps().stream().mapToInt(TaskStep::attempts).sum();
        require(attempts <= plan.maxRetriesPerStep() * plan.steps().size() + plan.steps().size(),
                what + " was attempted " + attempts + " time(s), past its own budget: " + plan.report());
        // The sentence, not the field. He said why, and the words survive the scrub and the chat cooldown.
        boolean said = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.saidRecently(cause)).orElse(false));
        require(said, what + " recorded the reason but never said it to the owner; he said " + said(server));
        System.out.println("[endings] " + what + ": " + plan.status() + " — \""
                + (plan.abortReason().isBlank() ? cause : plan.abortReason()) + "\"");
        driven.add(plan.steps().get(0).intent().action());
    }

    /**
     * The verbs actually driven, by name. A count of calls would overstate it — this class runs the same verb
     * through two different failures in places — so the closing line names how many distinct verbs ended
     * honestly rather than how many times it looked.
     */
    private final java.util.Set<String> driven = new java.util.LinkedHashSet<>();

    // ==================================================================
    // Fixtures and world reads
    // ==================================================================

    private static void emptyBag(CobbieEntity cobbie) {
        var inventory = cobbie.getInventoryContainer();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
    }

    private static boolean signReads(TestServerContext server, BlockPos pos, String words) {
        return server.computeOnServer(s -> signText(s, pos).toLowerCase(Locale.ROOT).contains(words));
    }

    private static String signText(net.minecraft.server.MinecraftServer s, BlockPos pos) {
        if (!(s.overworld().getBlockEntity(pos) instanceof SignBlockEntity sign)) return "";
        StringBuilder sb = new StringBuilder();
        for (var line : sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT)
                .getMessages(false)) {
            sb.append(line.getString()).append(' ');
        }
        return sb.toString().trim();
    }

    /** Wait until the step has carried him somewhere: a change made before the walk proves nothing. */
    private static void walkedAwayFrom(TestServerContext server, long since) {
        BlockPos start = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> !c.blockPosition().equals(start)).orElse(false), 200);
        } catch (Throwable t) {
            throw new AssertionError("He never set off, so nothing was mid-walk to change; plans: "
                    + describe(server, since), t);
        }
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
                if (record == null) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(record);
            }
            return sb.length() == 0 ? "(none)" : sb.toString();
        });
    }

    private static String said(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.recentLines().toString()).orElse("nothing"));
    }

    private static void require(boolean what, String why) {
        if (!what) throw new AssertionError(why);
    }
}
