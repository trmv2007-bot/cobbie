package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.OperationalMemory;
import com.rishi.cobbie.memory.OperationalMemory.Entry;
import com.rishi.cobbie.memory.OperationalMemory.Kind;
import com.rishi.cobbie.memory.WorldMemory;
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
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;
import java.util.UUID;

/**
 * The world wins: a memory is written down from what is really in the world, corrected when the world
 * changes, and dropped when the world says there is nothing there.
 *
 * <p>Two things are proved, and the second is the one that matters. First, a memory is what the *world*
 * says — the contents he wrote down came off a real chest's slots after a real take, not off what he
 * meant to do. Second, a memory is never evidence: with a memory naming a chest that is no longer
 * there, the verb refuses and nothing moves, and the memory that pointed at nothing is retired.
 *
 * <p>The anvil half does the same for a block: a chipped anvil, then a repaired one, then no anvil, and
 * the one memory follows the world through all three rather than repeating the first.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=MemoryWorld}.
 */
public final class CobbieMemoryWorldTest implements FabricClientGameTest {

    private static final String CHEST_SUBJECT = "chest";
    private static final int IRON_IN_CHEST = 40;
    private static final int TAKEN = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie autonomy off");
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.permit(server, "containers", true);
            CobbieTestSupport.permit(server, "crafting", true);

            UUID ownerId = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                OperationalMemory.clear(cobbie.getOwner());
                return cobbie.getOwner();
            });

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos chest = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + chest.getX() + " " + chest.getY() + " "
                        + chest.getZ() + " minecraft:chest");
                if (!(s.overworld().getBlockEntity(chest) instanceof net.minecraft.world.Container container)) {
                    throw new AssertionError("Setup failed: the square is not a container");
                }
                for (int i = 0; i < 4; i++) {
                    container.setItem(i, new ItemStack(Items.IRON_INGOT, 10));
                }
                return null;
            });

            // 1. A real take writes down what the chest holds now, read off its slots.
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("take 10 iron", List.of(new ActionIntent(ActionVocabulary.TAKE,
                            "minecraft:iron_ingot", chest.getX(), chest.getY(), chest.getZ(),
                            String.valueOf(TAKEN)))));
            if (refusal != null) throw new AssertionError("The take was refused: " + refusal);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            int carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:iron_ingot"));
            if (carried != TAKEN) {
                throw new AssertionError("He did not take the iron, so the memory below would be about nothing: "
                        + carried + " in his bag. " + CobbieTestSupport.lastReport(server));
            }
            Entry containerMemory = server.computeOnServer(s -> OperationalMemory.entries(ownerId).stream()
                    .filter(e -> e.kind() == Kind.CONTAINER).findFirst().orElse(null));
            if (containerMemory == null) {
                throw new AssertionError("Using a container left no memory of it. "
                        + OperationalMemory.listing(ownerId));
            }
            if (!containerMemory.fact().equals("holds 30 iron_ingot")) {
                throw new AssertionError("The memory does not say what the chest now holds: \""
                        + containerMemory.fact() + "\" (40 came out of it, 10 left)");
            }
            if (containerMemory.x() != chest.getX() || !containerMemory.at()) {
                throw new AssertionError("The memory does not carry the chest's square: " + containerMemory);
            }
            System.out.println("[gametest] container learned from the world: " + OperationalMemory.listing(ownerId).trim());

            // 2. Against a set of memories, the prompt gets the ones that bear on the request.
            CobbieTestSupport.command(server, "cobbie remember the boat is by the lake");
            CobbieTestSupport.command(server, "cobbie remember the roof is dangerous");
            CobbieTestSupport.command(server, "cobbie remember camp is at "
                    + (start.getX() + 300) + " " + start.getY() + " " + start.getZ());
            String block = server.computeOnServer(s -> WorldMemory.promptBlock(
                    CobbieTestSupport.cobbie(s).orElseThrow(), ownerId, "put the iron in the chest"));
            if (!block.contains(CHEST_SUBJECT)) {
                throw new AssertionError("The chest he just used is not in the prompt for a chest request: " + block);
            }
            if (!block.contains("holds 30 iron_ingot")) {
                throw new AssertionError("The prompt does not carry what the chest holds: " + block);
            }
            if (block.contains("boat") || block.contains("roof") || block.contains("camp")) {
                throw new AssertionError("Unrelated memories crowded the prompt: " + block);
            }
            if (block.lines().count() > WorldMemory.MAX_SHOWN || block.length() > OperationalMemory.MAX_PROMPT_CHARS) {
                throw new AssertionError("The prompt block is over its stated budget: " + block);
            }
            System.out.println("[gametest] retrieved for \"put the iron in the chest\": "
                    + String.join(" | ", block.lines().toList()));
            // 3. The world changes under the memory: it is corrected, not repeated.
            server.computeOnServer(s -> {
                if (!(s.overworld().getBlockEntity(chest) instanceof net.minecraft.world.Container container)) {
                    throw new AssertionError("The chest vanished before the correction stage");
                }
                // The take emptied slot 0, so the five go in as a fresh stack rather than onto an empty one.
                container.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
                WorldMemory.sweep(CobbieTestSupport.cobbie(s).orElseThrow());
                return null;
            });
            String corrected = server.computeOnServer(s -> OperationalMemory.entries(ownerId).stream()
                    .filter(e -> e.kind() == Kind.CONTAINER).map(Entry::fact).findFirst().orElse(""));
            if (!corrected.equals("holds 35 iron_ingot")) {
                throw new AssertionError("The memory was not corrected to what the chest now holds: \"" + corrected + "\"");
            }
            System.out.println("[gametest] corrected by the world: the chest now " + corrected);

            // 4. The chest is gone. The memory is a hint, so the verb refuses and nothing moves — and
            //    the memory that pointed at nothing is retired rather than repeated.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + chest.getX() + " " + chest.getY() + " "
                        + chest.getZ() + " minecraft:air");
                return null;
            });
            String putRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("put the iron in the chest", List.of(new ActionIntent(ActionVocabulary.PUT,
                            "minecraft:iron_ingot", chest.getX(), chest.getY(), chest.getZ(), "5"))));
            if (putRefusal != null) throw new AssertionError("The put was refused outright: " + putRefusal);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            String report = CobbieTestSupport.lastReport(server);
            if (!report.contains("nothing to put things in")) {
                throw new AssertionError("A put at an empty square did not say the container was gone: " + report);
            }
            int stillCarried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:iron_ingot"));
            if (stillCarried != TAKEN) {
                throw new AssertionError("He acted on the memory and moved the iron into nothing: bag holds "
                        + stillCarried + " of " + TAKEN);
            }
            server.computeOnServer(s -> {
                WorldMemory.sweep(CobbieTestSupport.cobbie(s).orElseThrow());
                return null;
            });
            boolean survived = server.computeOnServer(s -> OperationalMemory.entries(ownerId).stream()
                    .anyMatch(e -> e.kind() == Kind.CONTAINER));
            if (survived) {
                throw new AssertionError("A memory of a chest that no longer exists was kept: "
                        + OperationalMemory.listing(ownerId));
            }
            System.out.println("[gametest] memory retired: the verb refused (\"" + report.trim()
                    + "\"), the iron stayed in his bag, and the entry is gone");

            // 5. A durable fact about a block, following the world through chipped, repaired and gone.
            anvilPart(server, ownerId, start);
        }
    }

    /** An anvil memory is the block's own state, and it follows it: chipped, then intact, then nothing. */
    private void anvilPart(TestServerContext server, UUID ownerId, BlockPos start) {
        BlockPos anvil = new BlockPos(start.getX() - 2, start.getY() - 1, start.getZ());
        server.computeOnServer(s -> {
            CobbieTestSupport.command(s, "setblock " + anvil.getX() + " " + anvil.getY() + " "
                    + anvil.getZ() + " minecraft:chipped_anvil");
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.addItem(new ItemStack(Items.IRON_SWORD, 1));
            cobbie.addItem(sharpnessBook(s, 1));
            cobbie.setExperience(6, 0.0f);
            return null;
        });
        String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .offerPlan("put this book on my sword", List.of(new ActionIntent(ActionVocabulary.ANVIL,
                        "minecraft:iron_sword", 0, 0, 0, "minecraft:enchanted_book"))));
        if (refusal != null) throw new AssertionError("The anvil use was refused: " + refusal);
        CobbieTestSupport.awaitPlanSettled(server, 400);

        Entry atAnvil = server.computeOnServer(s -> OperationalMemory.entries(ownerId).stream()
                .filter(e -> e.kind() == Kind.BLOCK).findFirst().orElse(null));
        if (atAnvil == null) {
            throw new AssertionError("Working at an anvil left no memory of it. "
                    + OperationalMemory.listing(ownerId));
        }
        if (!"chipped".equals(atAnvil.fact())) {
            throw new AssertionError("The anvil's state was not read off the block: \"" + atAnvil.fact() + "\"");
        }
        if (atAnvil.x() != anvil.getX() || atAnvil.z() != anvil.getZ()) {
            throw new AssertionError("The anvil memory is not at the anvil: " + atAnvil);
        }
        System.out.println("[gametest] station learned from the world: " + atAnvil.subject() + " is " + atAnvil.fact());

        String repaired = server.computeOnServer(s -> {
            CobbieTestSupport.command(s, "setblock " + anvil.getX() + " " + anvil.getY() + " "
                    + anvil.getZ() + " minecraft:anvil");
            WorldMemory.sweep(CobbieTestSupport.cobbie(s).orElseThrow());
            return OperationalMemory.entries(ownerId).stream().filter(e -> e.kind() == Kind.BLOCK)
                    .map(Entry::fact).findFirst().orElse("");
        });
        if (!"intact".equals(repaired)) {
            throw new AssertionError("A repaired anvil was still remembered as chipped: \"" + repaired + "\"");
        }
        boolean gone = server.computeOnServer(s -> {
            CobbieTestSupport.command(s, "setblock " + anvil.getX() + " " + anvil.getY() + " "
                    + anvil.getZ() + " minecraft:air");
            WorldMemory.sweep(CobbieTestSupport.cobbie(s).orElseThrow());
            return OperationalMemory.entries(ownerId).stream().noneMatch(e -> e.kind() == Kind.BLOCK);
        });
        if (!gone) {
            throw new AssertionError("A memory of an anvil that is no longer there was kept: "
                    + OperationalMemory.listing(ownerId));
        }
        System.out.println("[gametest] station followed the world: chipped -> intact -> gone");
    }

    /**
     * A sharpness book, built through the enchantment API — the same helper and the same reason as
     * {@code CobbieAnvilTest}: {@code /enchant} on a held book leaves an empty stored-enchantment map
     * that the anvil refuses, so the command would make this test pass or fail for the wrong reason.
     */
    private static ItemStack sharpnessBook(MinecraftServer server, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        var lookup = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var sharpness = lookup.getOrThrow(Enchantments.SHARPNESS);
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(sharpness, level);
        book.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        return book;
    }
}
