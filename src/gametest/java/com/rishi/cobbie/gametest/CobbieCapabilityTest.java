package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.brain.CobbiePrompt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coverage sweep: every verb the model is told about must reach an executor, and the prompt must
 * actually name each permitted verb. A verb that exists in the vocabulary but has no executor case,
 * or is filtered out of the prompt, is a feature the AI can never use — which is exactly the kind of
 * gap this test is for.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieCapabilityTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // Explore is gated on autonomy, so give him some before asking about that verb.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            for (String sw : new String[]{"mining", "building", "combat", "exploration", "collection"}) {
                CobbieTestSupport.permit(server, sw, true);
            }

            BlockPos at = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());

            Map<String, String> outcomes = new LinkedHashMap<>();
            for (String verb : ActionVocabulary.allowed()) {
                // A few verbs are answered by the brain, not the body (a wiki lookup has no block to
                // touch). They must not claim entity coverage; they are checked below instead.
                if (ActionVocabulary.isBrainSide(verb)) continue;
                String outcome = server.computeOnServer(s -> {
                    var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    cobbie.interrupt();
                    ActionOutcome result = cobbie.begin(intentFor(verb, at));
                    return result == null ? "accepted (asynchronous)" : result.describe();
                });
                outcomes.put(verb, outcome);
                if (outcome.contains("unhandled verb")) {
                    throw new AssertionError("verb \"" + verb + "\" has no executor: " + outcome);
                }
            }

            // The prompt is the model's only knowledge of itself: every permitted verb must appear.
            String prompt = server.computeOnServer(s ->
                    CobbiePrompt.systemPrompt(null, null, null, null));
            for (String verb : ActionVocabulary.allowed()) {
                if (!prompt.contains(verb)) {
                    throw new AssertionError("verb \"" + verb + "\" is permitted but absent from the prompt");
                }
            }

            StringBuilder brainSide = new StringBuilder();
            for (String verb : ActionVocabulary.allowed()) {
                if (!ActionVocabulary.isBrainSide(verb)) continue;
                if (!prompt.contains(verb)) {
                    throw new AssertionError("brain-side verb \"" + verb + "\" is absent from the prompt");
                }
                brainSide.append(verb).append(' ');
            }

            outcomes.forEach((verb, outcome) -> System.out.println("[gametest] verb " + verb + " -> " + outcome));
            System.out.println("[gametest] brain-side verbs (answered in chat): " + brainSide.toString().trim());
            System.out.println("[gametest] capability ok: " + outcomes.size()
                    + " verbs reach an executor and appear in the prompt");
        }
    }

    /** A plausible intent per verb: nonsense targets are fine, "no executor" is not. */
    private static ActionIntent intentFor(String verb, BlockPos at) {
        return switch (verb) {
            case ActionVocabulary.WALK_TO ->
                    new ActionIntent(verb, "", at.getX() + 2, at.getY(), at.getZ() + 2, "");
            case ActionVocabulary.MINE ->
                    new ActionIntent(verb, "minecraft:stone", at.getX(), at.getY() + 2, at.getZ() + 3, "");
            case ActionVocabulary.PLACE ->
                    new ActionIntent(verb, "minecraft:dirt", at.getX(), at.getY() + 2, at.getZ() - 3, "");
            case ActionVocabulary.ATTACK -> new ActionIntent(verb, "minecraft:zombie", 0, 0, 0, "");
            case ActionVocabulary.COLLECT ->
                    new ActionIntent(verb, "", at.getX(), at.getY(), at.getZ(), "");
            case ActionVocabulary.CHAT -> new ActionIntent(verb, "hello", 0, 0, 0, "");
            default -> new ActionIntent(verb, "", 0, 0, 0, "");
        };
    }
}
