package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.List;

/**
 * Buying from a villager, and refusing a trade he cannot pay for.
 *
 * <p>The probe settled that {@code notifyTrade} works with no trading player (uses 0 → 1, +5 xp) and
 * that the merchant menu builds against the fake actor but is not needed. So the verb spends from his
 * own bag and lets the villager's own bookkeeping count the use and drive its restock.
 *
 * <p>Three claims, each read off the world: the item arrives and the price leaves his bag; the
 * villager's own use counter moves; and a trade he cannot afford is refused quoting the price rather
 * than attempted. The villager is a real farmer with its own generated trades, because a synthetic
 * offer on an unemployed villager does not survive — that was the probe's own first failure.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Trade}.
 */
public final class CobbieTradeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "containers", true);

            String item = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var level = s.overworld();
                Villager villager = new Villager(EntityTypes.VILLAGER, level);
                villager.setPos(cobbie.getX() + 1.5, cobbie.getY(), cobbie.getZ() + 1.0);
                villager.setPersistenceRequired();
                var professions = level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);
                var types = level.registryAccess().lookupOrThrow(Registries.VILLAGER_TYPE);
                villager.setVillagerData(new VillagerData(types.getOrThrow(VillagerType.PLAINS),
                        professions.getOrThrow(VillagerProfession.FARMER), 2));
                level.addFreshEntity(villager);
                cobbie.addItem(new ItemStack(Items.EMERALD, 6));
                return null;
            });
            if (item != null) throw new AssertionError("setup refused: " + item);

            // The first thing the farmer sells, taken from its own book rather than assumed.
            String wanted = server.computeOnServer(CobbieTradeTest::cheapestOfferItem);
            if (wanted == null) throw new AssertionError("Setup failed: the farmer offers nothing");

            // 1. With one emerald, a price he cannot meet is refused with the number.
            int price = server.computeOnServer(s -> priceOf(s, wanted));
            if (price > 1) {
                server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    cobbie.consumeItem("minecraft:emerald", cobbie.countItem("minecraft:emerald") - 1);
                    return null;
                });
                String broke = trade(server, wanted, "");
                if (broke != null) throw new AssertionError("trade refused outright: " + broke);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                if (!reportSays(server, "costs")) {
                    throw new AssertionError("A trade he cannot afford did not quote its price: "
                            + lastReport(server));
                }
                server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    cobbie.addItem(new ItemStack(Items.EMERALD, 8));
                    return null;
                });
            }

            // 2. Funded: the item arrives, the emeralds leave, and the villager counts the use.
            int usesBefore = server.computeOnServer(CobbieTradeTest::usesOfFirstOffer);
            int hadBefore = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem(wanted));
            int emeraldsBefore = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:emerald"));
            String asked = trade(server, wanted, "");
            if (asked != null) throw new AssertionError("trade refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 400);

            int hadAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem(wanted));
            int emeraldsAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:emerald"));
            int usesAfter = server.computeOnServer(CobbieTradeTest::usesOfFirstOffer);

            if (hadAfter <= hadBefore) {
                throw new AssertionError("The " + wanted + " never arrived: " + hadBefore + " -> " + hadAfter
                        + ". " + lastReport(server));
            }
            if (emeraldsAfter >= emeraldsBefore) {
                throw new AssertionError("The emeralds were not spent: " + emeraldsBefore + " -> " + emeraldsAfter);
            }
            if (usesAfter <= usesBefore) {
                throw new AssertionError("The villager did not count the trade: uses " + usesBefore + " -> "
                        + usesAfter);
            }

            // 3. Something the villager does not sell is refused with what it does sell.
            String nonsense = trade(server, "diamond", "");
            if (nonsense != null) throw new AssertionError("trade refused outright: " + nonsense);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            if (!reportSays(server, "offers")) {
                throw new AssertionError("An item the villager does not sell was not answered with its"
                        + " offers: " + lastReport(server));
            }

            System.out.println("[gametest] trade ok: bought " + wanted + " for emeralds ("
                    + emeraldsBefore + " -> " + emeraldsAfter + "), the villager counted the use ("
                    + usesBefore + " -> " + usesAfter + "); a price he could not meet was refused with the"
                    + " number, and an item not sold was answered with what is on offer");
        }
    }

    private static String trade(TestServerContext server, String item, String count) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "buy " + item,
                List.of(new ActionIntent(ActionVocabulary.TRADE, item, 0, 0, 0, count))));
    }

    private static Villager farmer(MinecraftServer server) {
        return server.overworld().getEntitiesOfClass(Villager.class,
                CobbieTestSupport.cobbie(server).orElseThrow().getBoundingBox().inflate(12))
                .stream().findFirst().orElse(null);
    }

    private static String cheapestOfferItem(MinecraftServer server) {
        Villager villager = farmer(server);
        if (villager == null || villager.getOffers().isEmpty()) return null;
        MerchantOffer offer = villager.getOffers().get(0);
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString();
    }

    private static int priceOf(MinecraftServer server, String wanted) {
        Villager villager = farmer(server);
        if (villager == null) return -1;
        for (MerchantOffer offer : villager.getOffers()) {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offer.getResult().getItem())
                    .toString().equals(wanted)) {
                return offer.getCostA().getCount();
            }
        }
        return -1;
    }

    private static int usesOfFirstOffer(MinecraftServer server) {
        Villager villager = farmer(server);
        return villager == null || villager.getOffers().isEmpty() ? -1 : villager.getOffers().get(0).getUses();
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
