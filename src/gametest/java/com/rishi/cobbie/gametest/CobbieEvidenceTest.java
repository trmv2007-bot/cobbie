package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionEvidence;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The four false successes of 0.79.0, re-induced so that going back to the old handler fails here.
 *
 * <p>{@code CobbieEndingsTest} asks what he says when the world refuses. This class asks the other half of
 * the 0.80.0 contract: <b>when he says something was done, what did he look at, and does the world agree
 * with the sentence rather than merely with the field?</b> A step is DONE only when its outcome carries
 * evidence the verb's own rule accepts ({@code TaskRunner.settle}), so each case here asserts three things
 * in one breath — the plan completed, the <b>world</b> holds the work, and the step's recorded observation
 * is the fact that proves it.
 *
 * <p>The four are the defects the endings inventory found, and each is measured at the point where it lied:
 *
 * <ul>
 *   <li><b>write</b> reported {@code arrived} over a blank sign — here the sign eight blocks away must read
 *       his words, and the evidence must be those words;</li>
 *   <li><b>farm</b> counted a crop over a discarded boolean — here the number he reports and the squares
 *       that are actually cut have to be the same number;</li>
 *   <li>ten station verbs discarded {@code addFreshEntity}, so a made thing could exist nowhere — here the
 *       mended pickaxe has to be findable in the world, and he has to say where;</li>
 *   <li>an item resolved to the wrong slot when two stacks shared id, count and damage — here a description
 *       that names the enchanted sword has to put the enchanted sword in his hand.</li>
 * </ul>
 *
 * <p>One honest limit, named rather than hidden: the farm case cannot induce a refused break. A vanilla flat
 * world has no protection system, so {@code destroyBlock} never says no — which is why the number is now
 * derived from the squares the world reports empty instead of from that answer at all, and why the count is
 * asserted against the world here rather than against the call.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Evidence --no-build-cache --rerun-tasks}.
 */
public final class CobbieEvidenceTest implements FabricClientGameTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED");
    /** Far enough that a step has to walk, short enough that the walk finishes inside the class. */
    private static final int AWAY = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            // Assistive, not creative: his own initiative projects would be a second plan in the window.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            CobbieTestSupport.permit(server, "explore", false);
            CobbieTestSupport.permit(server, "collection", true);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "crafting", true);
            CobbieTestSupport.permit(server, "containers", true);
            CobbieTestSupport.permit(server, "combat", true);
            awaitIdle(server, 400);

            theWriteCarriesTheSignsWords(server);
            theFarmsNumberIsTheWorlds(server);
            theMendedThingIsFindable(server);
            theDescriptionPicksTheSwordItNames(server);

            System.out.println("[evidence] four 0.79.0 defects re-induced: the sign's words, the crops"
                    + " counted off the field, the mended pickaxe findable in the world, and the enchanted"
                    + " sword actually in his hand");
        }
    }

    // ==================================================================
    // 1. write — arriving is not writing
    // ==================================================================

    private void theWriteCarriesTheSignsWords(TestServerContext server) {
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

        require(plan.status() == TaskPlan.Status.COMPLETED,
                "A write to a sign eight blocks away has to finish: " + plan.report());
        // The world, read by this test and never through the handler's sentence.
        require(signReads(server, sign, "coal for sale"),
                "FALSE SUCCESS: the plan completed and the sign reads \""
                        + server.computeOnServer(s -> signText(s, sign)) + "\" — " + plan.report());

        ActionEvidence seen = evidenceOf(plan, ActionVocabulary.WRITE);
        require(seen.aboutTheWorld(),
                "the write completed with no world observation behind it: " + seen.observed());
        require(seen.observed().toLowerCase(Locale.ROOT).contains("coal for sale"),
                "the write's evidence has to be the sign's own words, and it says \"" + seen.observed() + "\"");
        System.out.println("[evidence] write: the sign reads \"" + seen.observed() + "\"");
    }

    // ==================================================================
    // 2. farm — the number is the field's, not the loop's
    // ==================================================================

    private void theFarmsNumberIsTheWorlds(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
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
        TaskPlan plan = settledPlan(server, before, "harvest the wheat", 400,
                "A farm with two ripe crops never settled");

        final int[] cut = new int[1];
        int standing = server.computeOnServer(s -> {
            int left = 0;
            for (BlockPos p : ripe) {
                if (s.overworld().getBlockState(p).getBlock() == net.minecraft.world.level.block.Blocks.WHEAT) {
                    left++;
                } else {
                    cut[0]++;
                }
            }
            return left;
        });
        require(plan.status() == TaskPlan.Status.COMPLETED,
                "Two ripe crops he had no seeds to replant are still a harvest: " + plan.report());
        require(standing == 0, standing + " ripe crop(s) are still standing after a harvest that was reported");

        ActionEvidence seen = evidenceOf(plan, ActionVocabulary.FARM);
        require(seen.aboutTheWorld(),
                "the farm completed with no world observation behind it: " + seen.observed());
        // The reported number and the number of squares that actually lost their crop are the same number.
        require(seen.observed().startsWith(cut[0] + " of the " + ripe.size()),
                "he counted \"" + seen.observed() + "\" and the field lost " + cut[0] + " crop(s) of "
                        + ripe.size());
        require(plan.log().contains("harvested " + cut[0]),
                "the report says something other than the " + cut[0] + " he cut: " + plan.log());
        System.out.println("[evidence] farm: " + cut[0] + " of " + ripe.size() + " cut, "
                + standing + " standing — \"" + seen.observed() + "\"");
    }

    // ==================================================================
    // 3. the mended thing has to exist somewhere
    // ==================================================================

    private void theMendedThingIsFindable(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            for (int i = 0; i < 2; i++) {
                ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE, 1);
                worn.setDamageValue(100);
                cobbie.addItem(worn);
            }
            cobbie.offerPlan("mend my two pickaxes", List.of(new ActionIntent(
                    ActionVocabulary.REPAIR, "minecraft:diamond_pickaxe", 0, 0, 0, "")));
            return null;
        });
        TaskPlan plan = settledPlan(server, before, "mend my two pickaxes", 600,
                "A repair of two damaged pickaxes never settled");

        require(plan.status() == TaskPlan.Status.COMPLETED, "the mending must finish: " + plan.report());
        // Where the mended pickaxe is, counted by this test in the two places a made thing can be.
        int[] found = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            int inBag = 0;
            for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
                if (cobbie.getInventoryContainer().getItem(i).is(Items.DIAMOND_PICKAXE)) inBag++;
            }
            int onGround = s.overworld().getEntitiesOfClass(ItemEntity.class,
                    new AABB(cobbie.blockPosition()).inflate(4.0),
                    e -> e.getItem().is(Items.DIAMOND_PICKAXE)).size();
            return new int[] {inBag, onGround};
        });
        require(found[0] + found[1] == 1,
                "FALSE SUCCESS: he said he mended two pickaxes into one and there are " + (found[0] + found[1])
                        + " of them in the world (" + found[0] + " in his bag, " + found[1]
                        + " on the ground) — " + plan.report());

        ActionEvidence seen = evidenceOf(plan, ActionVocabulary.REPAIR);
        require(seen.aboutTheWorld(),
                "the repair completed with no world observation behind it: " + seen.observed());
        String where = seen.observed().toLowerCase(Locale.ROOT);
        require(where.contains("in my bag") || where.contains("on the ground"),
                "he has to say where the mended thing went, and he says \"" + seen.observed() + "\"");
        System.out.println("[evidence] repair: " + (found[0] == 1 ? "the bag holds it" : "it is on the ground")
                + " — \"" + seen.observed() + "\"");
    }

    // ==================================================================
    // 4. the sword a description names is the sword he picks up
    // ==================================================================

    private void theDescriptionPicksTheSwordItNames(TestServerContext server) {
        awaitIdle(server, 60);
        long before = latestId(server);
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            emptyBag(cobbie);
            // Two swords that an id-count-damage match cannot tell apart: same item, same count, same
            // damage, and only one of them carries the enchantment the description names. The plain one
            // goes in first, so the old slot search found it first.
            cobbie.addItem(new ItemStack(Items.DIAMOND_SWORD, 1));
            ItemStack sharp = new ItemStack(Items.DIAMOND_SWORD, 1);
            ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            mutable.set(s.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.SHARPNESS), 5);
            sharp.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
            cobbie.addItem(sharp);
            return null;
        });
        server.computeOnServer(s -> {
            CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("hold my sharpness sword",
                    List.of(new ActionIntent(ActionVocabulary.EQUIP, "sharpness 5", 0, 0, 0, "")));
            return null;
        });
        TaskPlan plan = settledPlan(server, before, "hold my sharpness sword", 400,
                "An equip of the sword a description names never settled");

        require(plan.status() == TaskPlan.Status.COMPLETED, "the equip must finish: " + plan.report());
        int held = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            ItemStack inHand = cobbie.getMainHandItem();
            var enchantments = inHand.get(DataComponents.ENCHANTMENTS);
            if (enchantments == null) return 0;
            for (var entry : enchantments.entrySet()) {
                if (entry.getKey().is(Enchantments.SHARPNESS)) return entry.getIntValue();
            }
            return 0;
        });
        require(held == 5,
                "FALSE SUCCESS: he said he was holding the sharpness 5 sword and the one in his hand has"
                        + " sharpness " + held + " — " + plan.report());

        ActionEvidence seen = evidenceOf(plan, ActionVocabulary.EQUIP);
        require(seen.aboutTheWorld(), "the equip completed with no world observation behind it");
        System.out.println("[evidence] equip: his hand holds sharpness " + held + " — \"" + seen.observed() + "\"");
    }

    // ==================================================================
    // Reading a finished plan
    // ==================================================================

    /**
     * The observation a step recorded, and a refusal when there is none.
     *
     * <p>Every one of these cases completes, so a missing observation is not a corner case to tolerate: it
     * would mean a step reached DONE without the contract applying to it.
     */
    private static ActionEvidence evidenceOf(TaskPlan plan, String verb) {
        for (TaskStep step : plan.steps()) {
            if (!verb.equals(step.intent().action())) continue;
            if (step.status() != TaskStep.Status.DONE) {
                throw new AssertionError(verb + " is " + step.status() + " and this case needs it done: "
                        + step.describe());
            }
            if (step.evidence() == null) {
                throw new AssertionError(verb + " reached DONE with nothing it saw: " + plan.report());
            }
            return step.evidence();
        }
        throw new AssertionError("no " + verb + " step in: " + plan.report());
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

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
}
