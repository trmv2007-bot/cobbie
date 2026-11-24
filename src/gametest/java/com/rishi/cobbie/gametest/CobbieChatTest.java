package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.brain.ConnectionState;
import com.rishi.cobbie.memory.CobbieMemory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;

import java.util.UUID;

/**
 * The end-to-end brain path with the live model: type at him in chat, and require that a reply is
 * actually produced and recorded. Asserts a reply exists, never its wording.
 *
 * Run with {@code ./gradlew runClientGameTest} with Ollama up.
 */
public final class CobbieChatTest implements FabricClientGameTest {

    private static final String PROMPT = "cobbie hello there, say something friendly";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.awaitBrain(server);

            UUID ownerId = server.computeOnServer(s -> CobbieTestSupport.owner(s).getUUID());

            // Memory persists between runs, so remember the reply that already exists: a stale answer
            // from an earlier session must never be mistaken for a reply to this message.
            //
            // The predicate is the *last reply differing*, not a count growing. A count is a proxy, and
            // it broke exactly as proxies do: with a history already holding this same question from an
            // earlier run, the new reply evicted an old one and the count never moved, so the test
            // reported "no reply reached memory" while the reply sat in the history it printed. Anything
            // else writing to the same memory would have done the same.
            String before = server.computeOnServer(s -> lastReply(ownerId));

            // Send the message the way a player does, through the real chat channel.
            context.runOnClient(mc -> mc.getConnection().sendChat(PROMPT));

            try {
                server.waitFor(s -> !lastReply(ownerId).equals(before), 1800);
            } catch (Throwable t) {
                ConnectionState state = CobbieBrain.lastState();
                String detail = CobbieBrain.lastStateDetail();
                String history = server.computeOnServer(s -> String.valueOf(CobbieMemory.history(ownerId, 4)));
                throw new AssertionError("No reply reached memory within 90s; brain state=" + state
                        + " detail=" + detail + " history=" + history, t);
            }

            String answer = server.computeOnServer(s -> lastReply(ownerId));
            if (answer.contains("think>") || answer.contains("We need to respond")) {
                throw new AssertionError("Reasoning leaked into the reply: " + answer);
            }
            System.out.println("[gametest] chat ok: reply length=" + answer.length()
                    + " first line=\"" + answer.lines().findFirst().orElse("") + "\"");
        }
    }

    /** The newest non-blank thing he said, or "" when he has said nothing yet. */
    private static String lastReply(UUID ownerId) {
        return CobbieMemory.history(ownerId, 40).stream()
                .filter(m -> "assistant".equals(m.role()) && !m.content().isBlank())
                .reduce((a, b) -> b)
                .map(CobbieMemory.Message::content)
                .orElse("");
    }
}
