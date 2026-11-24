package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.List;

/**
 * A banner pattern at a real loom, chosen by the name of the pattern <em>item</em>.
 *
 * <p>The claim is the layer that comes back: the banner must carry the creeper pattern in the colour
 * of the dye he used, the pattern item and the dye must be spent, and the banner must be one banner
 * rather than two. A refusal for a pattern he does not have must cost nothing.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Loom}.
 */
public final class CobbieLoomTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "crafting", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos loom = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + loom.getX() + " " + loom.getY() + " "
                        + loom.getZ() + " minecraft:loom");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.BANNER.white(), 1));
                cobbie.addItem(new ItemStack(Items.DYE.red(), 2));
                cobbie.addItem(new ItemStack(Items.CREEPER_BANNER_PATTERN, 1));
                return null;
            });

            // 1. A pattern he does not have is refused, and costs nothing.
            String refused = weave(server, "flower_banner_pattern", "red");
            if (refused != null) throw new AssertionError("weave refused outright: " + refused);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "i have no")) {
                throw new AssertionError("A pattern he does not carry was not refused with the reason: "
                        + lastReport(server));
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:red_dye")) != 2) {
                throw new AssertionError("The refused weave spent his dye");
            }

            // 2. The creeper pattern he named goes onto the banner.
            String asked = weave(server, "creeper_banner_pattern", "red");
            if (asked != null) throw new AssertionError("weave refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 600);

            // The item landing is not the claim; the step reporting success is. A weave that
            // patterned the banner and then said it had failed is what the verb-choice harness caught.
            String status = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(status)) {
                throw new AssertionError("The weave did not report success (" + status + "): "
                        + lastReport(server));
            }
            // The sentence the owner reads is part of the claim: reading the item id after the stack
            // was spent used to make it say "wove minecraft:air".
            if (!reportSays(server, "wove minecraft:creeper_banner_pattern onto the banner in minecraft:red_dye")) {
                throw new AssertionError("The report does not name what was woven: " + lastReport(server));
            }
            String result = server.computeOnServer(CobbieLoomTest::describeBanner);
            if (!result.equals("ok")) throw new AssertionError(result + ". " + lastReport(server));
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:creeper_banner_pattern")) != 0) {
                throw new AssertionError("The pattern item was not spent");
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:red_dye")) != 1) {
                throw new AssertionError("Exactly one dye should have been spent");
            }

            System.out.println("[gametest] loom ok: creeper pattern in red on the white banner, chosen by"
                    + " naming the pattern item; the pattern and one dye were spent; a pattern he does not"
                    + " have was refused and cost nothing");
        }
    }

    private static String weave(TestServerContext server, String pattern, String colour) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "put the " + pattern + " on my banner",
                List.of(new ActionIntent(ActionVocabulary.WEAVE, pattern, 0, 0, 0, colour))));
    }

    /** "ok", or a sentence naming what is wrong with the banner in his bag. */
    private static String describeBanner(MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return "no companion";
        int banners = 0;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.BANNER.white())) continue;
            banners += stack.getCount();
            BannerPatternLayers layers = stack.get(DataComponents.BANNER_PATTERNS);
            if (layers == null || layers.layers().isEmpty()) return "the banner has no pattern on it";
            boolean creeper = layers.layers().stream().anyMatch(layer -> layer.pattern().unwrapKey()
                    .map(key -> key.identifier().getPath().equals("creeper"))
                    .orElse(false));
            if (!creeper) return "the pattern on the banner is " + layers.layers() + ", not creeper";
            boolean red = layers.layers().stream().anyMatch(layer -> layer.color() == net.minecraft.world.item.DyeColor.RED);
            if (!red) return "the pattern came out " + layers.layers().get(0).color() + ", not red";
        }
        if (banners != 1) return "he is carrying " + banners + " white banners instead of 1";
        return "ok";
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
