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

            // Memory persists between runs, so count the replies that already exist: a stale answer
            // from an earlier session must never be mistaken for a reply to this message.
            int before = server.computeOnServer(s -> replyCount(ownerId));

            // Send the message the way a player does, through the real chat channel.
            context.runOnClient(mc -> mc.getConnection().sendChat(PROMPT));

            try {
                server.waitFor(s -> replyCount(ownerId) > before, 1800);
            } catch (Throwable t) {
                ConnectionState state = CobbieBrain.lastState();
                String detail = CobbieBrain.lastStateDetail();
                String history = server.computeOnServer(s -> String.valueOf(CobbieMemory.history(ownerId, 4)));
                throw new AssertionError("No reply reached memory within 90s; brain state=" + state
                        + " detail=" + detail + " history=" + history, t);
            }

            String answer = server.computeOnServer(s -> CobbieMemory.history(ownerId, 40).stream()
                    .filter(m -> "assistant".equals(m.role()))
                    .reduce((a, b) -> b)
                    .map(CobbieMemory.Message::content)
                    .orElse(""));
            if (answer.contains("think>") || answer.contains("We need to respond")) {
                throw new AssertionError("Reasoning leaked into the reply: " + answer);
            }
            System.out.println("[gametest] chat ok: reply length=" + answer.length()
                    + " first line=\"" + answer.lines().findFirst().orElse("") + "\"");
        }
    }

    private static int replyCount(UUID ownerId) {
        return (int) CobbieMemory.history(ownerId, 40).stream()
                .filter(m -> "assistant".equals(m.role()) && !m.content().isBlank())
                .count();
    }
}
