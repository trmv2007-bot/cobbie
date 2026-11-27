package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.opportunity.Opportunity;
import com.rishi.cobbie.opportunity.OpportunityAssessor;
import com.rishi.cobbie.opportunity.OpportunityCycle;
import com.rishi.cobbie.opportunity.OpportunitySelector;
import com.rishi.cobbie.opportunity.Shortlist;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What he may do when nobody is watching: the hunting rule and the hazard guard, each driven in a live
 * world with a control beside its case.
 *
 * <p>The two holes this class exists for were both proven in this suite before it was written — he would
 * not touch an animal at all, so a companion with a hunger bar starved beside a herd, and he would prime
 * TNT wherever he was pointed, including beside the owner. Both are policy rather than API, so both are
 * proved as policy: a predicate, a refusal line, and the world left alone when the answer is no.
 *
 * <p><b>The controls are the point.</b> The pen case is one cow, one hunger state and one fence that is
 * either there or not: the same rule must say yes on open ground and no inside a pen, or the test is
 * measuring something else. The TNT case is the same block lit by the same hand at two distances.
 *
 * <p><b>What the harness cannot drive honestly.</b> The model choosing a hunt is injected at
 * {@link OpportunitySelector#parse} and {@link OpportunityCycle#apply}, exactly as in
 * {@code CobbieOpportunityTest}, because no model answers at 11434 on this machine and the suite has to run
 * without one. Everything after that boundary — the assessor, the plan runner, the swing, the guard, the
 * refusal line — is the shipped code. That the owner's <em>remembered</em> places are part of the radius is
 * proved by {@code HazardsTest}'s arithmetic and by the wiring being one list; no case here builds a memory
 * entry, so the memory source is named as proved-by-unit-test rather than by this run.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Permission}.
 */
public final class CobbiePermissionTest implements FabricClientGameTest {

    /** Where the two cows are put, recorded so no case has to find them by walking. */
    private static BlockPos penned = BlockPos.ZERO;
    private static BlockPos grazing = BlockPos.ZERO;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "combat", true);
            CobbieTestSupport.permit(server, "collection", true);
            CobbieTestSupport.permit(server, "building", true);
            // His own cycle stands down: every decision here is either injected or asked directly, and a
            // plan starting underneath an assertion is the flakiest thing in this suite.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.command(server, "gamerule advanceTime false");
            CobbieTestSupport.command(server, "gamerule doMobSpawning false");
            CobbieTestSupport.command(server, "time set noon");

            // Hungry with nothing to eat: the only state the hunting rule allows. His own eating policy
            // looks for food at fourteen, so six is a companion who would starve without hunting.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.getNavigation().stop();
                cobbie.setHunger(6, 0.0f);
                BlockPos feet = cobbie.blockPosition();
                // The pen is well outside the fight radius, so nothing the grazing cow does over the next
                // five seconds can carry it into the pen's eight-block radius and make it read as kept —
                // which is exactly how this case failed twice at six and at eleven blocks.
                penned = feet.offset(18, 0, 6).immutable();
                grazing = feet.offset(-5, 0, -3).immutable();
                spawnCow(s, penned);
                spawnCow(s, grazing);
                return null;
            });
            CobbieTestSupport.elapsed(server, 20);

            List<String> failures = new ArrayList<>();

            // ---- 1. The pen: the same animal, the same hunger, a fence around one of them. ----
            run(failures, "an animal in a pen is not food", server, () -> {
                if (!server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().mayHunt())) {
                    throw new AssertionError("he is not hungry, so this case cannot be about the fence");
                }
                // The control first: on open ground the same rule says yes.
                boolean open = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .isLegalTarget(cowIn(s, grazing)));
                if (!open) {
                    throw new AssertionError("a cow on open ground is not a legal target while he is hungry"
                            + " with nothing to eat, so hunting was never enabled");
                }
                fenceRing(server, penned, 2);
                boolean kept = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .isLegalTarget(cowIn(s, penned)));
                if (kept) {
                    throw new AssertionError("a cow inside a fenced pen is still a legal target: the pen"
                            + " clause of the hunting rule is not applied");
                }
                // And no candidate offers to swing at it.
                BlockPos pennedAt = server.computeOnServer(s -> cowIn(s, penned).blockPosition());
                for (Opportunity o : assess(server).items()) {
                    for (ActionIntent step : o.steps()) {
                        if (!ActionVocabulary.ATTACK.equals(step.action())) continue;
                        if (new BlockPos(step.x(), step.y(), step.z()).distSqr(pennedAt) > 25) continue;
                        throw new AssertionError("the shortlist offered to kill the penned cow: " + o.brief());
                    }
                }
                float before = server.computeOnServer(s -> cowIn(s, penned).getHealth());
                CobbieTestSupport.elapsed(server, 100);
                float after = server.computeOnServer(s -> cowIn(s, penned).getHealth());
                if (after < before) {
                    throw new AssertionError("the penned cow was hurt: " + before + " -> " + after);
                }
                System.out.println("[gametest] permission: the same cow reads legal on open ground and illegal"
                        + " inside a fence, and the penned one is untouched at " + after);
            });

            // ---- 2. The hunt: hungry, no food, and the kill happens. ----
            run(failures, "a hungry companion kills to eat", server, () -> {
                String probe = server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    Cow cow = cowIn(s, grazing);
                    return "mayHunt=" + cobbie.mayHunt() + " food=" + cobbie.foodLevel()
                            + " legal=" + cobbie.isLegalTarget(cow)
                            + " dist=" + (int) Math.sqrt(cobbie.distanceToSqr(cow))
                            + " huntable=" + (cobbie.huntableNear(16) != null)
                            + " bag=" + cobbie.carriedSummary();
                });
                System.out.println("[gametest] permission probe: " + probe);
                Shortlist list = assess(server);
                String cowId = firstHunt(list);
                if (cowId == null) {
                    throw new AssertionError("no hunt was offered to a hungry companion with nothing to eat"
                            + " and a cow in the field: " + list.describe());
                }
                String request = server.computeOnServer(s -> list.byId(cowId).request());
                UUID prey = server.computeOnServer(s -> cowIn(s, grazing).getUUID());
                String started = choose(server, list, cowId);
                if (started != null) {
                    throw new AssertionError("the chosen hunt did not start: " + started);
                }
                try {
                    server.waitFor(s -> {
                        Entity found = s.overworld().getEntity(prey);
                        return found == null || !found.isAlive();
                    }, 1200);
                } catch (Throwable t) {
                    throw new AssertionError("the cow was never killed: " + lastReport(server), t);
                }
                String report = lastReport(server);
                // The meat is the receipt, and it may already have been eaten: his own eating policy fires
                // within a second at food six, which is the point of hunting at all. So the bar rising is
                // the claim, and the bag or the ground beside it is the alternate shape of the same fact.
                boolean fed = server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    // countItem, not countOf: the latter resolves a block name, so "beef" reads as zero
                    // whatever is in the bag — which is how the first run of this case blamed the code.
                    // The ground is searched around him rather than around where the cow started: a hurt
                    // cow runs before it dies, and one run had it die eleven blocks from its own square.
                    return cobbie.foodLevel() > 6
                            || cobbie.countItem("minecraft:beef") > 0
                            || cobbie.countItem("minecraft:leather") > 0
                            || !s.overworld().getEntitiesOfClass(ItemEntity.class,
                                    new AABB(cobbie.blockPosition()).inflate(24),
                                    e -> e.getItem().is(Items.BEEF) || e.getItem().is(Items.LEATHER)).isEmpty();
                });
                int food = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().foodLevel());
                if (!fed) {
                    throw new AssertionError("the cow died and nothing came of it: his food bar is still " + food
                            + ", no beef or leather in his bag and none on the ground beside it — " + report);
                }
                System.out.println("[gametest] permission: \"" + request + "\" killed the cow and fed him to"
                        + " food " + food + " — " + report);
            });

            // ---- 3. TNT beside the owner is refused, and the refusal says why. ----
            run(failures, "TNT beside the owner is refused", server, () -> {
                BlockPos bomb = server.computeOnServer(s -> {
                    BlockPos at = CobbieTestSupport.owner(s).blockPosition().offset(3, 0, 0);
                    CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                            + " minecraft:tnt");
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.FLINT_AND_STEEL, 1));
                    return at;
                });
                String refused = use(server, "flint_and_steel", bomb);
                if (refused != null) {
                    throw new AssertionError("the plan was refused outright instead of the step saying why: "
                            + refused);
                }
                CobbieTestSupport.awaitPlanSettled(server, 400);
                String report = lastReport(server);
                if (!report.contains("I will not prime that TNT")) {
                    throw new AssertionError("nothing refused the TNT beside the owner; the plan said: "
                            + report);
                }
                if (!report.contains("from you")) {
                    throw new AssertionError("the refusal did not name what it was protecting: " + report);
                }
                int primed = server.computeOnServer(s -> s.overworld()
                        .getEntitiesOfClass(PrimedTnt.class, new AABB(bomb).inflate(8)).size());
                if (primed > 0) {
                    throw new AssertionError("he primed it anyway: " + primed + " lit TNT beside the owner");
                }
                System.out.println("[gametest] permission: " + report.trim());
            });

            // ---- 4. The same block, the same hand, out in the field: allowed. ----
            run(failures, "TNT in the middle of nowhere is allowed", server, () -> {
                CobbieTestSupport.teleportOwner(server, 24, 0, 0);
                BlockPos bomb = server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    BlockPos at = cobbie.blockPosition().offset(2, 0, 0);
                    CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                            + " minecraft:tnt");
                    cobbie.addItem(new ItemStack(Items.FLINT_AND_STEEL, 1));
                    return at;
                });
                CobbieTestSupport.elapsed(server, 5);
                String refused = use(server, "flint_and_steel", bomb);
                if (refused != null) {
                    throw new AssertionError("refused outright with the owner twenty-four blocks away: "
                            + refused);
                }
                try {
                    server.waitFor(s -> !s.overworld().getEntitiesOfClass(PrimedTnt.class,
                            new AABB(bomb).inflate(8)).isEmpty(), 400);
                } catch (Throwable t) {
                    throw new AssertionError("TNT far from the owner was not primed either, so the guard is a"
                            + " ban rather than a radius: " + lastReport(server), t);
                }
                server.computeOnServer(s -> {
                    s.overworld().getEntitiesOfClass(PrimedTnt.class, new AABB(bomb).inflate(8))
                            .forEach(PrimedTnt::discard);
                    return null;
                });
                System.out.println("[gametest] permission: the same flint and steel primes TNT with the owner"
                        + " out of range, so the rule is a radius and not a ban");
            });

            // ---- 5. Lava and a bare fire are the same rule, because they are the same list. ----
            run(failures, "lava and fire are refused by the same guard", server, () -> {
                CobbieTestSupport.teleportOwner(server, -24, 0, 0);
                BlockPos stone = server.computeOnServer(s -> {
                    BlockPos at = CobbieTestSupport.owner(s).blockPosition().offset(2, 0, 0);
                    CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                            + " minecraft:stone");
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.LAVA_BUCKET, 1));
                    // A fire charge rather than a second flint and steel: two identical items make the
                    // resolver ask which one, which is a refusal about the bag and not about the guard.
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.FIRE_CHARGE, 1));
                    return at;
                });
                String lavaPlan = use(server, "lava_bucket", stone);
                if (lavaPlan != null) throw new AssertionError("lava plan refused outright: " + lavaPlan);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                String lavaReport = lastReport(server);
                if (!lavaReport.contains("I will not pour that lava")) {
                    throw new AssertionError("lava beside the owner was not refused: " + lavaReport);
                }
                String firePlan = use(server, "fire_charge", stone);
                if (firePlan != null) throw new AssertionError("fire plan refused outright: " + firePlan);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                String fireReport = lastReport(server);
                if (!fireReport.contains("I will not set that alight")) {
                    throw new AssertionError("a fire beside the owner was not refused: " + fireReport);
                }
                System.out.println("[gametest] permission: " + lavaReport.trim() + " | " + fireReport.trim());
            });

            // ---- 6. Unprompted tidying stops at the owner's things. ----
            run(failures, "his own tidying stays out of what the owner built", server, () -> {
                // The owner names a place, and two identical piles of stone go down: one inside the
                // radius of that place and one outside it. The blocks are indistinguishable, so the
                // difference the shortlist shows is the memory and nothing else.
                BlockPos feet = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .blockPosition());
                BlockPos named = feet.offset(0, 0, 12);
                List<BlockPos> keptPile = List.of(feet.offset(0, 0, 4), feet.offset(0, 0, 5),
                        feet.offset(0, 0, 6));
                List<BlockPos> freePile = List.of(feet.offset(0, 0, -4), feet.offset(0, 0, -5),
                        feet.offset(0, 0, -6));
                CobbieTestSupport.teleportOwner(server, 24, 0, 0);
                server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    com.rishi.cobbie.memory.WorldMemory.learnFromOwner(cobbie, cobbie.getOwner(), "home",
                            "you told me this is home", true,
                            named.getX(), named.getY(), named.getZ());
                    cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
                    for (BlockPos pos : List.of(keptPile.get(0), keptPile.get(1), keptPile.get(2),
                            freePile.get(0), freePile.get(1), freePile.get(2))) {
                        CobbieTestSupport.command(s, "setblock " + pos.getX() + " " + pos.getY() + " "
                                + pos.getZ() + " minecraft:stone");
                    }
                    return null;
                });
                Shortlist list = assess(server);
                boolean keptOffered = false;
                boolean freeOffered = false;
                for (Opportunity o : list.items()) {
                    for (ActionIntent step : o.steps()) {
                        if (!ActionVocabulary.MINE.equals(step.action())) continue;
                        BlockPos at = new BlockPos(step.x(), step.y(), step.z());
                        if (at.distSqr(named) <= 64) keptOffered = true;
                        if (at.distSqr(named) > 64) freeOffered = true;
                    }
                }
                if (keptOffered) {
                    throw new AssertionError("he offered to mine stone inside eight blocks of a place the"
                            + " owner named as home: " + list.describe());
                }
                if (!freeOffered) {
                    throw new AssertionError("he offered to mine nothing at all, so the radius is a ban"
                            + " rather than a guard: " + list.describe());
                }
                System.out.println("[gametest] permission: the same stone is offered outside eight blocks of a"
                        + " remembered place and refused inside it — " + list.describe());
            });

            // ---- 7. A creeper beside the owner's things is not worth walking to. ----
            run(failures, "he does not go after a creeper beside them", server, () -> {
                server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    BlockPos feet = cobbie.blockPosition();
                    com.rishi.cobbie.memory.WorldMemory.learnFromOwner(cobbie, cobbie.getOwner(), "workshop",
                            "you told me this is the workshop", true, feet.getX(), feet.getY(), feet.getZ());
                    BlockPos near = feet.offset(3, 0, 0);
                    CobbieTestSupport.command(s, "summon minecraft:creeper " + (near.getX() + 0.5) + " "
                            + near.getY() + " " + (near.getZ() + 0.5) + " {NoAI:1b,PersistenceRequired:1b}");
                    return null;
                });
                CobbieTestSupport.elapsed(server, 10);
                Shortlist guarded = assess(server);
                if (offersCreeper(guarded)) {
                    throw new AssertionError("he offered to go after a creeper standing beside a place the"
                            + " owner named: " + guarded.describe());
                }
                // The control: the same creeper, moved out of the radius, is a fight worth offering.
                server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    CobbieTestSupport.command(s, "kill @e[type=minecraft:creeper]");
                    BlockPos far = cobbie.blockPosition().offset(12, 0, 0);
                    CobbieTestSupport.command(s, "summon minecraft:creeper " + (far.getX() + 0.5) + " "
                            + far.getY() + " " + (far.getZ() + 0.5) + " {NoAI:1b,PersistenceRequired:1b}");
                    return null;
                });
                CobbieTestSupport.elapsed(server, 10);
                Shortlist after = assess(server);
                if (!offersCreeper(after)) {
                    throw new AssertionError("a creeper twelve blocks out is not offered either, so the guard"
                            + " is a ban and not a radius: " + after.describe());
                }
                System.out.println("[gametest] permission: the creeper beside the workshop is left alone and the"
                        + " one twelve blocks out is offered — " + after.describe());
            });

            // Hand the world back quiet. The harness closes a world by halting the server from the render
            // thread, and a companion still asking his own cycle for work, or a creeper still standing in
            // the field, leaves the two threads waiting on each other inside that halt — the run that
            // first wrote this class hung for forty minutes after every case had already passed.
            CobbieTestSupport.command(server, "cobbie autonomy off");
            CobbieTestSupport.command(server, "kill @e[type=minecraft:creeper]");
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).ifPresent(c -> c.getNavigation().stop());
                return null;
            });
            CobbieTestSupport.awaitQuietBrain(server);
            CobbieTestSupport.elapsed(server, 20);

            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " of 7 permission cases failed: "
                        + String.join(" | ", failures));
            }
            System.out.println("[gametest] permission ok: a penned cow survives and a grazing one is eaten,"
                    + " TNT is refused beside the owner and primed away from them, lava and fire refused, and"
                    + " his own tidying and hunting stop at what the owner built");
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface Case {
        void run() throws Throwable;
    }

    private static void run(List<String> failures, String what, TestServerContext server, Case body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(what + " — " + t.getMessage());
        }
    }

    private static void spawnCow(MinecraftServer server, BlockPos at) {
        Cow cow = new Cow(EntityTypes.COW, server.overworld());
        cow.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cow.setPersistenceRequired();
        server.overworld().addFreshEntity(cow);
    }

    /** The living cow nearest a square: a cow that wandered two blocks is the same cow. */
    private static Cow cowIn(MinecraftServer server, BlockPos at) {
        return server.overworld().getEntitiesOfClass(Cow.class, new AABB(at).inflate(6), Cow::isAlive).stream()
                .min((a, b) -> Double.compare(a.blockPosition().distSqr(at), b.blockPosition().distSqr(at)))
                .orElseThrow();
    }

    private static void fenceRing(TestServerContext server, BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                BlockPos pos = centre.offset(dx, 0, dz);
                CobbieTestSupport.command(server, "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " minecraft:oak_fence");
            }
        }
    }

    private static Shortlist assess(TestServerContext server) {
        return server.computeOnServer(s -> OpportunityAssessor.assess(CobbieTestSupport.cobbie(s).orElseThrow()));
    }

    /** The first hunt on the list: a replenish whose plan is a swing at an animal. */
    private static String firstHunt(Shortlist list) {
        for (Opportunity o : list.items()) {
            if (o.kind() != Opportunity.Kind.REPLENISH) continue;
            for (ActionIntent step : o.steps()) {
                if (ActionVocabulary.ATTACK.equals(step.action())) return o.id();
            }
        }
        return null;
    }

    /** Whether the shortlist offers to go after a creeper. */
    private static boolean offersCreeper(Shortlist list) {
        for (Opportunity o : list.items()) {
            if (o.kind() != Opportunity.Kind.FIGHT) continue;
            for (ActionIntent step : o.steps()) {
                if ("minecraft:creeper".equals(step.target())) return true;
            }
        }
        return false;
    }

    /** The model's answer, injected where it would cross the boundary, and the dispatcher that ships. */
    private static String choose(TestServerContext server, Shortlist list, String id) {
        return server.computeOnServer(s -> OpportunityCycle.apply(CobbieTestSupport.cobbie(s).orElseThrow(),
                list, OpportunitySelector.parse(list, "{\"choice\":\"" + id + "\"}")));
    }

    private static String use(TestServerContext server, String item, BlockPos at) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "use my " + item, List.of(new ActionIntent(ActionVocabulary.USE, item,
                        at.getX(), at.getY(), at.getZ(), ""))));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
