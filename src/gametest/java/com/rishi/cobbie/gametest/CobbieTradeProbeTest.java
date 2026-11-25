package com.rishi.cobbie.gametest;

import com.rishi.cobbie.actor.FakeActor;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Optional;

/**
 * A probe, not a test: whether a villager's trades can be driven without a player in the world.
 *
 * <p>Trading is the last of the menu-shaped capabilities, and the brief asks a specific question first:
 * does the villager's trade menu open for a fake actor at all, and does writing the offer slots behave
 * the way the smithing table's did — or does this menu need a {@code Player} in a way the others did
 * not? Guessing either way would have cost more than this probe.
 *
 * <p>It also asks the cheaper question underneath: whether the villager's own bookkeeping
 * ({@code notifyTrade}, which counts uses and restocks) works with no trading player at all. If it does,
 * the whole verb is "spend from the bag, add the result, tell the villager", and no menu is needed.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=TradeProbe}. Prints, never asserts.
 */
public final class CobbieTradeProbeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var level = s.overworld();

                Villager villager = new Villager(EntityTypes.VILLAGER, level);
                villager.setPos(cobbie.getX() + 1.5, cobbie.getY(), cobbie.getZ() + 1.0);
                villager.setPersistenceRequired();
                level.addFreshEntity(villager);

                // A synthetic offer on an unemployed villager does not survive: overrideOffers left
                // getOffers() empty (IndexOutOfBounds on get(0)), because a villager's trade list is
                // rebuilt from its profession. So give it one and read the trades it really offers —
                // more faithful anyway, since that is what a player's villager actually has.
                var professions = level.registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION);
                var types = level.registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_TYPE);
                villager.setVillagerData(new net.minecraft.world.entity.npc.villager.VillagerData(
                        types.getOrThrow(net.minecraft.world.entity.npc.villager.VillagerType.PLAINS),
                        professions.getOrThrow(net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER),
                        2));
                System.out.println("[probe] offers=" + villager.getOffers().size()
                        + " costA=" + villager.getOffers().get(0).getCostA()
                        + " result=" + villager.getOffers().get(0).getResult()
                        + " uses=" + villager.getOffers().get(0).getUses());

                // --- 1. the menu route, the way the anvil and the smithing table were driven ---
                try {
                    // The actor is created by FakeActor itself; the probe reaches it the same way the
                    // real call sites do, through a use on the villager, and then builds the menu from
                    // its inventory.
                    var menu = new net.minecraft.world.inventory.MerchantMenu(0,
                            FakeActor.actorFor(level).getInventory(), villager);
                    menu.getSlot(0).set(new ItemStack(Items.EMERALD, 1));
                    menu.getSlot(2).set(new ItemStack(Items.EMERALD, 1));
                    System.out.println("[probe] merchant menu: slots=" + menu.slots.size()
                            + " resultSlot=" + menu.getSlot(2).getItem());
                } catch (Throwable t) {
                    System.out.println("[probe] merchant menu threw: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
                }

                // --- 2. the cheap route: does the villager's own bookkeeping run with no customer? ---
                try {
                    MerchantOffer offer = villager.getOffers().get(0);
                    int usesBefore = offer.getUses();
                    villager.notifyTrade(offer);
                    System.out.println("[probe] notifyTrade: uses " + usesBefore + " -> " + offer.getUses()
                            + " xp=" + villager.getVillagerXp() + " tradingPlayer=" + villager.getTradingPlayer());
                } catch (Throwable t) {
                    System.out.println("[probe] notifyTrade threw: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
                }
                System.out.println("[probe] done");
                return null;
            });
        }
    }
}
