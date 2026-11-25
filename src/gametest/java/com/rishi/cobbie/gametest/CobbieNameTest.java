package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbiePrompt;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;

import java.util.UUID;

/**
 * Naming him: the name lives on the entity, is shown everywhere the old constant was, refuses what would
 * break its own format, and survives a reload.
 *
 * <p>Each assertion is one place a name goes wrong in a shipped mod: a nameplate that disagrees with the
 * panel, a chat prefix that still says the default, a prompt that still calls him Cobbie while the world
 * calls him something else, a colour code rendering as colour, and a rename that quietly forgets him on
 * the next load.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Name}.
 */
public final class CobbieNameTest implements FabricClientGameTest {

    private static final String NAME = "Sir Fluff";

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;

        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieEntity spawn = CobbieTestSupport.spawn(context, server);
            if (!"Cobbie".equals(spawn.companionName())) {
                throw new AssertionError("A companion with no name given is called \""
                        + spawn.companionName() + "\", not the default");
            }
            CobbieTestSupport.command(server, "cobbie stay");

            // --- the name is refused when it would break the format ---
            CobbieTestSupport.command(server, "cobbie name Sir §cFluff");
            reboot(server, spawn, "A colour code in the middle of a name was accepted");
            CobbieTestSupport.command(server, "cobbie name " + "A".repeat(25));
            reboot(server, spawn, "A 25-character name was accepted");
            CobbieTestSupport.command(server, "cobbie name    ");
            reboot(server, spawn, "A blank name was accepted");

            // --- it is accepted, trimmed, and shown everywhere ---
            CobbieTestSupport.command(server, "cobbie name   " + NAME + "  ");
            String carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .companionName());
            if (!NAME.equals(carried)) {
                throw new AssertionError("He is called \"" + carried + "\" after being named \"" + NAME
                        + "\" (the name should be trimmed, not kept verbatim)");
            }

            String nameplate = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.setCustomNameVisible(true);
                return cobbie.getCustomName() == null ? "" : cobbie.getCustomName().getString();
            });
            if (!nameplate.startsWith("§e" + NAME + " §8· §7")) {
                throw new AssertionError("The nameplate reads \"" + nameplate + "\" instead of \"§e" + NAME
                        + " §8· §7<status>\"");
            }

            String panel = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .panelData().name());
            if (!NAME.equals(panel)) {
                throw new AssertionError("The panel header reads \"" + panel + "\" while the nameplate"
                        + " says \"" + NAME + "\"");
            }

            String line = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .says("hello there").getString());
            if (!line.contains("[" + NAME + "]")) {
                throw new AssertionError("His own chat line reads \"" + line + "\", without his name on it");
            }

            String prompt = server.computeOnServer(s -> CobbiePrompt.systemPrompt(
                    CobbieTestSupport.cobbie(s).orElseThrow().companionName(), null, "", "", ""));
            if (!prompt.contains("You are " + NAME)) {
                throw new AssertionError("His prompt still does not know his name: it says \""
                        + prompt.substring(0, Math.min(80, prompt.length())) + "...\"");
            }

            // --- nothing keyed by him is orphaned by the rename ---
            UUID owner = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getOwner());
            int level = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().experienceLevel());
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().renameTo("Mallow");
                return null;
            });
            UUID ownerAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getOwner());
            int levelAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().experienceLevel());
            if (!owner.equals(ownerAfter) || level != levelAfter) {
                throw new AssertionError("Renaming changed his owner (" + owner + " -> " + ownerAfter
                        + ") or his level (" + level + " -> " + levelAfter + "); memory and skills are keyed"
                        + " by owner and must not move");
            }
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().renameTo(NAME);
                return null;
            });

            System.out.println("[gametest] name ok: \"" + NAME + "\" on the nameplate (\"" + nameplate
                    + "\"), in the panel, on his chat line (\"" + line + "\") and in his prompt; a colour"
                    + " code, a 25-character name and a blank were all refused; owner and level unchanged");

            save = world.getWorldSave();
        }

        try (TestSingleplayerContext reopened = save.open()) {
            TestServerContext server = reopened.getServer();
            CobbieTestSupport.awaitWorld(context);
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 600);
            } catch (Throwable t) {
                throw new AssertionError("He was not there after relogging into the saved world", t);
            }
            String survived = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .companionName());
            if (!NAME.equals(survived)) {
                throw new AssertionError("After saving and reloading he is called \"" + survived
                        + "\", not \"" + NAME + "\"");
            }
            String plateAfter = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                return cobbie.getCustomName() == null ? "" : cobbie.getCustomName().getString();
            });
            if (!plateAfter.contains(NAME)) {
                throw new AssertionError("The name survived the reload but the nameplate reads \""
                        + plateAfter + "\"");
            }
            System.out.println("[gametest] name ok: \"" + survived + "\" survived a save and reload — plate"
                    + " reads \"" + plateAfter + "\"");
        }
    }

    /** An invalid name must leave the old one untouched. */
    private static void reboot(TestServerContext server, CobbieEntity spawn, String what) {
        String carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .companionName());
        if (!"Cobbie".equals(carried)) {
            throw new AssertionError(what + ": he is now called \"" + carried + "\"");
        }
    }
}
