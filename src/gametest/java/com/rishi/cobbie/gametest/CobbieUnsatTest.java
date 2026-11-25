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
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * An instruction that only a combination could satisfy.
 *
 * <p>Every verb refuses sensibly on its own; nothing had asked what happens when the answer needs a *chain* — planks
 * to sticks to a torch — or when it needs one ingredient that is absent and cannot be made. The rule is the same two
 * endings as the rest of the suite: <b>he finds the chain, or he names the missing link.</b> The failure this class
 * exists to catch is the third outcome: announcing a plan that cannot complete.
 *
 * <p>Three cases, and the third is the one that matters:
 * <ol>
 *   <li>"make me a torch" with coal and sticks and no crafting table — satisfiable, and the chain is what a craft
 *       planner is for;</li>
 *   <li>"make a bed" with wool and planks and no table — the recipe needs a 3×2 grid, so the honest endings are a bed
 *       he actually carries or a refusal that says what stopped him, and never a bed he does not have;</li>
 *   <li>"make a bed" with wool and <b>no planks and no logs</b> — an ingredient that is absent and cannot be made. He
 *       must refuse, naming it, and <b>no plan may be announced</b>: the assertion is on the plan count, not on the
 *       absence of a completion.</li>
 * </ol>
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Unsat}.
 */
public final class CobbieUnsatTest implements FabricClientGameTest {

    private static final String TORCH = "minecraft:torch";
    private static final String BED = "minecraft:white_bed";
    private static final String WOOL = "minecraft:white_wool";
    private static final String PLANKS = "minecraft:oak_planks";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.permit(server, "crafting", true);
            // Nothing of his own may be running while an instruction is judged, and a wandering companion would put
            // a project of his own in the plan count this class asserts on.
            CobbieTestSupport.permit(server, "explore", false);
            CobbieTestSupport.command(server, "cobbie autonomy assistive");

            // --- 1. A torch from coal and sticks, with no crafting table. ---
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.COAL, 2));
                cobbie.addItem(new ItemStack(Items.STICK, 4));
                return null;
            });
            Attempt torch = attempt(server, "make me a torch", TORCH, 1);
            int torches = count(server, TORCH);
            System.out.println("[unsat] 1. \"make me a torch\" with coal and sticks, no table: " + torch.summary()
                    + "; torches now " + torches + ", carrying " + inventory(server));
            if (torch.refusal() != null && !namesWhatIsMissing(torch.refusal())) {
                throw new AssertionError("The torch instruction was refused without naming anything missing: "
                        + torch.refusal());
            }
            if (torch.planId() > 0 && !torch.settled()) {
                throw new AssertionError("The torch project is still running: " + torch.record());
            }
            if (torches == 0 && !namesWhatIsMissing(torch.explanation())) {
                throw new AssertionError("No torch was made and nothing explained what was missing: "
                        + torch.explanation());
            }
            assertNothingRunning(server, "after the torch");

            // --- 2. A bed from wool and planks, with no crafting table. ---
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(stack(WOOL, 4));
                cobbie.addItem(stack(PLANKS, 6));
                return null;
            });
            Attempt bed = attempt(server, "make a bed", BED, 1);
            int beds = count(server, BED);
            System.out.println("[unsat] 2. \"make a bed\" with wool and planks, no table: " + bed.summary()
                    + "; beds now " + beds + ", carrying " + inventory(server));
            if (bed.refusal() != null && !namesWhatIsMissing(bed.refusal())) {
                throw new AssertionError("The bed instruction was refused without naming anything missing: "
                        + bed.refusal());
            }
            if (beds == 0 && bed.status() != null && bed.status().equals("COMPLETED")) {
                throw new AssertionError("He reported a completed bed he does not carry: " + bed.record());
            }
            if (beds == 0 && !namesWhatIsMissing(bed.explanation())) {
                throw new AssertionError("No bed was made and nothing explained what was missing: "
                        + bed.explanation());
            }
            assertNothingRunning(server, "after the bed");

            // --- 3. A bed with wool and nothing to make planks from: the one that must be refused. ---
            server.computeOnServer(s -> {
                // Empty him first: the bed case above leaves a bed in his bag, and "I already have 1" is a true
                // answer to "make a bed" — the fixture has to take that out of the question, not the code.
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                for (int slot = 0; slot < cobbie.getInventoryContainer().getContainerSize(); slot++) {
                    cobbie.getInventoryContainer().setItem(slot, ItemStack.EMPTY);
                }
                // Wool only: the bed needs planks, planks need logs, and there are none to make them from.
                cobbie.addItem(stack(WOOL, 3));
                return null;
            });
            // What the game itself says a bed is made of, so a refusal naming the wrong ingredient can be traced
            // to the recipe rather than argued about.
            String recipes = server.computeOnServer(s -> {
                StringBuilder sb = new StringBuilder();
                for (var match : com.rishi.cobbie.craft.CraftRecipes.allFor(s.overworld(), BED)) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(match.recipe().id()).append(" <- ").append(match.recipe().slots());
                }
                return sb.length() == 0 ? "(the game has no recipe for " + BED + ")" : sb.toString();
            });
            System.out.println("[unsat] 3. recipes the game offers for " + BED + ": " + recipes);
            long plansBefore = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .latestPlanId());
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("make a bed", List.of(new ActionIntent(ActionVocabulary.CRAFT, BED, 0, 0, 0, "1"))));
            CobbieTestSupport.elapsed(server, 60);
            long plansAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            String line = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().lastSpokenLine());
            System.out.println("[unsat] 3. \"make a bed\" with wool and no planks, no logs: refusal=\""
                    + refusal + "\", plans " + plansBefore + " -> " + plansAfter + ", last line \"" + line + "\"");
            if (plansAfter != plansBefore) {
                throw new AssertionError("A plan was announced for an instruction that cannot be satisfied; it reads "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .planRecord(plansAfter)));
            }
            if (refusal == null || refusal.isBlank()) {
                throw new AssertionError("Nothing was said about an instruction that cannot be satisfied");
            }
            String named = (refusal + " " + line).toLowerCase(Locale.ROOT);
            if (!named.contains("plank") && !named.contains("log")) {
                throw new AssertionError("The refusal does not name the missing ingredient: \"" + refusal + "\"");
            }
            System.out.println("[unsat] done");
        }
    }

    /** What became of one instruction, gathered from his own state rather than assumed. */
    private record Attempt(String request, String target, String refusal, long planId, String status, String record,
            String explanation) {

        String summary() {
            if (refusal != null) return "REFUSED: \"" + refusal + "\"";
            return (status == null ? "no plan" : status) + " — " + record;
        }

        boolean settled() {
            return status == null || status.equals("COMPLETED") || status.equals("FAILED")
                    || status.equals("CANCELLED") || status.equals("EXPIRED");
        }
    }

    private static Attempt attempt(TestServerContext server, String request, String target, int quantity) {
        long before = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
        String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .offerPlan(request, List.of(new ActionIntent(ActionVocabulary.CRAFT, target, 0, 0, 0,
                        String.valueOf(quantity)))));
        if (refusal != null) return new Attempt(request, target, refusal, -1, null, refusal, refusal);
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> c.lastPlan() != null
                    && c.lastPlan().id() > before && c.lastPlan().isFinished()).orElse(false), 600);
        } catch (Throwable t) {
            // Still running: reported, not guessed at.
            String running = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.planRecord(c.latestPlanId())).orElse("no companion"));
            return new Attempt(request, target, null, before + 1, "RUNNING", running, running);
        }
        String record = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            TaskPlan plan = cobbie.planAfter(before);
            return plan == null ? "no record" : plan.request() + " -> " + plan.status() + " — " + plan.report();
        });
        String status = statusIn(record);
        String line = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().lastSpokenLine());
        return new Attempt(request, target, null, before + 1, status, record, record + " said: " + line);
    }

    /** Does this text say what he is short of, rather than only that it did not work? */
    private static boolean namesWhatIsMissing(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("no recipe") || lower.contains("have no") || lower.contains("none in my bag")
                || lower.contains("cannot make") || lower.contains("can't make") || lower.contains("ran out of")
                || lower.contains("missing") || lower.contains("i need");
    }

    private static void assertNothingRunning(TestServerContext server, String when) {
        boolean busy = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().isBusy());
        if (busy) {
            throw new AssertionError("A project was left running " + when + ": "
                    + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .planRecord(CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId())));
        }
    }

    private static String statusIn(String record) {
        int mark = record.indexOf(" -> ");
        if (mark < 0) return null;
        int end = record.indexOf(" — ", mark + 4);
        return record.substring(mark + 4, end < 0 ? record.length() : end);
    }

    /**
     * An item by registry id. The named constants for wool and beds are colour collections in 26.3, and a test that
     * spells the registry id measures the same thing without following the Java-side naming.
     */
    private static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(net.minecraft.resources.Identifier.parse(id))
                .orElseThrow(() -> new AssertionError("no item called " + id + " in this version"));
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(item(id), count);
    }

    private static int count(TestServerContext server, String id) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).map(c -> c.countItem(id)).orElse(-1));
    }

    private static String inventory(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::describeInventory).orElse("no companion"));
    }
}
