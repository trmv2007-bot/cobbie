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
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * His hunger bar, and eating and drinking, measured against the game rather than against my arithmetic.
 *
 * <p>The claim behind every case here is that the <em>game</em> owns the numbers: {@code FoodData} is
 * vanilla's class, {@code FoodData.tick} is vanilla's tick, the nutrition is the item's own
 * {@code FOOD} component, and the exhaustion an action costs is the constant vanilla applies for that
 * action. So each case asserts a figure that was read from the game in the same run — the bread's
 * nutrition is compared with what the bread says it is, the starvation's damage is compared with the
 * health the body had, the bowl is compared with the item's own remainder — and none of it is compared
 * with a constant typed by hand.
 *
 * <p>What this class cannot drive honestly is named where it is skipped: the use <em>duration</em> of a
 * player's 1.6-second meal is not spent in ticks, because a companion's action is synchronous. The
 * outcome the game produces at the end of that duration is what is asserted, through the game's own
 * {@code finishUsingItem}.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Hunger}.
 */
public final class CobbieHungerTest implements FabricClientGameTest {

    private static final float EPS = 1.0e-3f;

    /** Everything the client is told, in order — the same collector the panel test uses. */
    private static final List<String> HEARD = Collections.synchronizedList(new ArrayList<>());

    static {
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> HEARD.add(message.getString()));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        synchronized (HEARD) {
            HEARD.clear();
        }
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.permit(server, "mining", true);

            sprintRule(server);
            mushroomStewReturnsItsBowl(server);
            regenerationIsTheGames(server);
            starvationIsTheGames(server);
            breadUsesTheItemsOwnNutrition(server);
            fullBarRefusesFoodButNotAGoldenApple(server);
            heEatsTheWorstFoodFirst(server);
            milkClearsAHarmfulEffect(server);
            aHealingPotionWhenBadlyHurt(server);
            breakingABlockCostsTheGamesExhaustion(server);
            everyoneCanSeeIt(server);

            System.out.println("[gametest] hunger ok: the game's own nutrition, exhaustion, regeneration,"
                    + " starvation, effect clearing, return items and food bar were all read back");
        }
    }

    // ------------------------------------------------------------------
    // The game's sprint rule
    // ------------------------------------------------------------------

    /**
     * A player whose food is at {@code FoodConstants.SPRINT_LEVEL} cannot sprint, and neither can he.
     * The predicate is the game's own ({@code hasEnoughFoodToDoExhaustiveManoeuvres}), asked through the
     * actor whose food bar is his.
     */
    private void sprintRule(TestServerContext server) {
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(FoodConstants.SPRINT_LEVEL, 0.0f);
            System.out.println("[gametest] hunger probe: food=" + cobbie.foodLevel()
                    + " maySprint=" + cobbie.canSprintNow() + " " + cobbie.hungerDebug());
            cobbie.setSprinting(true);
            if (cobbie.isSprinting()) {
                throw new AssertionError("At food " + FoodConstants.SPRINT_LEVEL
                        + " he sprinted, which a player at that food cannot do");
            }
            cobbie.setHunger(FoodConstants.SPRINT_LEVEL + 1, 0.0f);
            cobbie.setSprinting(true);
            if (!cobbie.isSprinting()) {
                throw new AssertionError("At food " + (FoodConstants.SPRINT_LEVEL + 1)
                        + " he refused to sprint, which a player at that food may do");
            }
            cobbie.setSprinting(false);
            return null;
        });
        System.out.println("[gametest] hunger: sprint refused at food " + FoodConstants.SPRINT_LEVEL
                + " and allowed at " + (FoodConstants.SPRINT_LEVEL + 1));
    }

    // ------------------------------------------------------------------
    // Consuming through the item
    // ------------------------------------------------------------------

    /**
     * A bowl of stew: the stack shrinks by one, and the stack the game hands back is the item's own
     * remainder — a bowl — rather than an item invented here.
     */
    private void mushroomStewReturnsItsBowl(TestServerContext server) {
        int nutrition = server.computeOnServer(s -> foodOf(Items.MUSHROOM_STEW).nutrition());
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(12, 0.0f);
            cobbie.addItem(new ItemStack(Items.MUSHROOM_STEW, 1));
            if (!cobbie.eatSomething()) {
                throw new AssertionError("He would not eat the only food he carried at food 12");
            }
            if (cobbie.countItem("minecraft:mushroom_stew") != 0) {
                throw new AssertionError("The stew did not shrink: he still carries "
                        + cobbie.countItem("minecraft:mushroom_stew"));
            }
            if (cobbie.countItem("minecraft:bowl") != 1) {
                throw new AssertionError("The bowl did not come back: he carries "
                        + cobbie.countItem("minecraft:bowl"));
            }
            if (cobbie.foodLevel() != 12 + nutrition) {
                throw new AssertionError("Stew raised food from 12 to " + cobbie.foodLevel()
                        + " and the item says its nutrition is " + nutrition);
            }
            return null;
        });
        System.out.println("[gametest] hunger: stew consumed, food +" + nutrition + ", bowl returned");
    }

    // ------------------------------------------------------------------
    // The consequences
    // ------------------------------------------------------------------

    /**
     * At full food and any saturation, a hurt player heals one point every
     * {@code FoodConstants.HEALTH_TICK_COUNT_SATURATED} ticks. He is hurt, kept full, and ticked exactly
     * that long: the health he gains is the game's.
     */
    private void regenerationIsTheGames(TestServerContext server) {
        int soak = FoodConstants.HEALTH_TICK_COUNT_SATURATED;
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHealth(10.0f);
            // Six saturation is the point where the game's saturated heal is a whole point
            // (min(saturation, 6) / 6): below it the heal is a fraction of one, and the assertion would be
            // about a fraction rather than about the game's cadence.
            cobbie.setHunger(FoodConstants.MAX_FOOD, 6.0f);
            return null;
        });
        CobbieTestSupport.elapsed(server, soak);
        float health = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getHealth());
        if (Math.abs(health - 11.0f) > EPS) {
            throw new AssertionError("At food 20 and hurt, " + soak + " ticks left him at " + health
                    + " health; the game's saturated regeneration is one point per " + soak + " ticks");
        }
        System.out.println("[gametest] hunger: one point of health in " + soak
                + " ticks at full food (health 10 -> " + health + ")");
    }

    /**
     * At zero food a hurt player takes one point of starvation damage every
     * {@code FoodConstants.HEALTH_TICK_COUNT} ticks. No food in the bag, so his own eating cannot
     * rescue him, and the health he loses is the game's number against the health he had.
     */
    private void starvationIsTheGames(TestServerContext server) {
        int soak = FoodConstants.HEALTH_TICK_COUNT + 10;
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHealth(20.0f);
            cobbie.setHunger(FoodConstants.STARVE_LEVEL, 0.0f);
            return null;
        });
        CobbieTestSupport.elapsed(server, soak);
        float health = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getHealth());
        if (Math.abs(health - 19.0f) > EPS) {
            throw new AssertionError("At food 0 and 20 health, " + soak + " ticks left him at " + health
                    + "; the game's starvation damage is one point per " + FoodConstants.HEALTH_TICK_COUNT
                    + " ticks");
        }
        boolean saidStarving = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .lastSpokenLine()).toLowerCase().contains("starving");
        if (!saidStarving) {
            throw new AssertionError("He starved without saying so: last line was "
                    + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().lastSpokenLine()));
        }
        System.out.println("[gametest] hunger: starvation took one health in " + soak
                + " ticks and he said he was starving");
    }

    // ------------------------------------------------------------------
    // Eating policy
    // ------------------------------------------------------------------

    /**
     * Bread raises food by exactly what bread says it raises it by; the figure is read from the game's
     * own {@code FOOD} component in this run, not written here.
     */
    private void breadUsesTheItemsOwnNutrition(TestServerContext server) {
        FoodProperties bread = server.computeOnServer(s -> foodOf(Items.BREAD));
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(10, 0.0f);
            cobbie.addItem(new ItemStack(Items.BREAD, 3));
            if (!cobbie.eatSomething()) {
                throw new AssertionError("He would not eat bread at food 10");
            }
            if (cobbie.foodLevel() != 10 + bread.nutrition()) {
                throw new AssertionError("Bread raised food from 10 to " + cobbie.foodLevel()
                        + " and the item's own nutrition is " + bread.nutrition());
            }
            if (cobbie.countItem("minecraft:bread") != 2) {
                throw new AssertionError("A stack of three bread did not shrink to two: "
                        + cobbie.countItem("minecraft:bread"));
            }
            float expectedSaturation = Math.min(bread.saturation(), 10 + bread.nutrition());
            if (Math.abs(cobbie.saturationLevel() - expectedSaturation) > EPS) {
                throw new AssertionError("Saturation is " + cobbie.saturationLevel() + " and the game's"
                        + " own figure for one bread on an empty bar is " + expectedSaturation);
            }
            return null;
        });
        System.out.println("[gametest] hunger: bread raised food by its own nutrition ("
                + bread.nutrition() + ") and shrank 3 -> 2");
    }

    /**
     * A full bar refuses ordinary food — through the game's own {@code Player.canEat} — while a golden
     * apple is still allowed, because its own component says it may always be eaten.
     */
    private void fullBarRefusesFoodButNotAGoldenApple(TestServerContext server) {
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(FoodConstants.MAX_FOOD, FoodConstants.MAX_SATURATION);
            int before = cobbie.countItem("minecraft:bread");
            if (cobbie.eatSomething()) {
                throw new AssertionError("He ate at a full bar, which the game refuses for ordinary food");
            }
            if (cobbie.countItem("minecraft:bread") != before) {
                throw new AssertionError("A refused meal still spent the bread");
            }
            cobbie.addItem(new ItemStack(Items.GOLDEN_APPLE, 1));
            if (!cobbie.eatSomething()) {
                throw new AssertionError("He refused a golden apple at a full bar, which the game allows");
            }
            if (cobbie.countItem("minecraft:golden_apple") != 0) {
                throw new AssertionError("The golden apple was not consumed");
            }
            if (cobbie.foodLevel() != FoodConstants.MAX_FOOD) {
                throw new AssertionError("Eating at a full bar moved food to " + cobbie.foodLevel());
            }
            return null;
        });
        System.out.println("[gametest] hunger: full bar refused bread and allowed a golden apple");
    }

    /**
     * Worst food first: with bread (5) and cooked beef (8) in the bag at food 6, the bread goes — not the
     * steak — so he is not burning his best food at nineteen out of twenty.
     */
    private void heEatsTheWorstFoodFirst(TestServerContext server) {
        int breadNutrition = server.computeOnServer(s -> foodOf(Items.BREAD).nutrition());
        int beefNutrition = server.computeOnServer(s -> foodOf(Items.COOKED_BEEF).nutrition());
        if (breadNutrition >= beefNutrition) {
            throw new AssertionError("The test's premise is wrong: bread " + breadNutrition
                    + " is not worse than cooked beef " + beefNutrition);
        }
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(6, 0.0f);
            int bread = cobbie.countItem("minecraft:bread");
            cobbie.addItem(new ItemStack(Items.COOKED_BEEF, 1));
            if (!cobbie.eatSomething()) {
                throw new AssertionError("He would not eat at food 6 with two kinds of food in his bag");
            }
            if (cobbie.countItem("minecraft:bread") != bread - 1) {
                throw new AssertionError("He did not eat the bread first: bread " + bread + " -> "
                        + cobbie.countItem("minecraft:bread"));
            }
            if (cobbie.countItem("minecraft:cooked_beef") != 1) {
                throw new AssertionError("He ate the cooked beef before the bread");
            }
            if (cobbie.foodLevel() != 6 + breadNutrition) {
                throw new AssertionError("After the bread he is at " + cobbie.foodLevel() + " food, not "
                        + (6 + breadNutrition));
            }
            return null;
        });
        System.out.println("[gametest] hunger: bread (" + breadNutrition + ") eaten before beef ("
                + beefNutrition + ") at food 6");
    }

    /**
     * Milk, through the item's own use: the game's {@code ClearAllStatusEffectsConsumeEffect} removes the
     * poison, and the item's remainder is the empty bucket the game defines for it.
     */
    private void milkClearsAHarmfulEffect(TestServerContext server) {
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
            if (!cobbie.hasEffect(MobEffects.POISON)) {
                throw new AssertionError("The test's own poison was not applied to him");
            }
            cobbie.addItem(new ItemStack(Items.MILK_BUCKET, 1));
            if (!cobbie.drinkSomething()) {
                throw new AssertionError("He carried milk and had a harmful effect, and did not drink");
            }
            if (cobbie.hasEffect(MobEffects.POISON)) {
                throw new AssertionError("Milk did not clear the poison");
            }
            if (cobbie.countItem("minecraft:milk_bucket") != 0) {
                throw new AssertionError("The milk bucket did not shrink");
            }
            if (cobbie.countItem("minecraft:bucket") != 1) {
                throw new AssertionError("The empty bucket did not come back: he carries "
                        + cobbie.countItem("minecraft:bucket"));
            }
            return null;
        });
        System.out.println("[gametest] hunger: milk cleared poison and returned its bucket");
    }

    /**
     * A healing potion when he is badly hurt. The heal itself is the game's own instant-health effect,
     * applied through {@code ItemStack.use} on an actor whose effects and health are his.
     */
    private void aHealingPotionWhenBadlyHurt(TestServerContext server) {
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHealth(4.0f);
            cobbie.addItem(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            float before = cobbie.getHealth();
            if (!cobbie.drinkSomething()) {
                throw new AssertionError("At " + before + " health he carried a healing potion and did not drink");
            }
            float after = cobbie.getHealth();
            if (after <= before) {
                throw new AssertionError("The healing potion did not heal him: health " + before + " -> " + after);
            }
            if (cobbie.countItem("minecraft:potion") != 0) {
                throw new AssertionError("The potion did not shrink");
            }
            if (cobbie.countItem("minecraft:glass_bottle") != 1) {
                throw new AssertionError("The empty bottle did not come back");
            }
            return null;
        });
        System.out.println("[gametest] hunger: a healing potion raised his health and returned its bottle");
    }

    // ------------------------------------------------------------------
    // Exhaustion from what he does
    // ------------------------------------------------------------------

    /**
     * Breaking one block costs one block's exhaustion: {@code FoodConstants.EXHAUSTION_MINE}, the number
     * {@code Block.playerDestroy} charges a player. Measured as the difference across a real break, with
     * the block read back as air so the charge is tied to a break that happened.
     */
    private void breakingABlockCostsTheGamesExhaustion(TestServerContext server) {
        // He is set down one block from the stone so the walk to it costs nothing: the measurement is
        // about the break, not about the path, and a jumped ledge would add 0.05 of its own.
        BlockPos feet = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition().offset(3, 0, 0));
        BlockPos target = feet.east();
        server.computeOnServer(s -> {
            s.overworld().setBlockAndUpdate(target,
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            return null;
        });
        CobbieTestSupport.stand(server, feet);
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(FoodConstants.MAX_FOOD, 0.0f);
            cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
            return null;
        });
        float before = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().exhaustionLevel());
        server.computeOnServer(s -> {
            CobbieTestSupport.cobbie(s).orElseThrow().mine(target);
            return null;
        });
        try {
            server.waitFor(s -> s.overworld().getBlockState(target).isAir(), 400);
        } catch (Throwable t) {
            throw new AssertionError("The stone was never broken, so any exhaustion figure would be about"
                    + " nothing", t);
        }
        float after = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().exhaustionLevel());
        CobbieTestSupport.elapsed(server, 2);
        if (Math.abs((after - before) - FoodConstants.EXHAUSTION_MINE) > EPS) {
            throw new AssertionError("Breaking one block added " + (after - before)
                    + " exhaustion; the game's own figure is " + FoodConstants.EXHAUSTION_MINE);
        }
        System.out.println("[gametest] hunger: one broken block added " + (after - before)
                + " exhaustion (the game's " + FoodConstants.EXHAUSTION_MINE + ")");
    }

    // ------------------------------------------------------------------
    // What a person sees
    // ------------------------------------------------------------------

    private void everyoneCanSeeIt(TestServerContext server) {
        String[] lines = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHunger(13, 2.0f);
            return new String[] {cobbie.hungerLine(), cobbie.panelData().hunger()};
        });
        String line = lines[0];
        if (!line.contains("food 13/20") || !line.contains("saturation 2.0")) {
            throw new AssertionError("The hunger line is not the three game numbers: \"" + line + "\"");
        }
        if (lines[1] == null || !lines[1].contains("food 13/20")) {
            throw new AssertionError("The panel does not carry his hunger: \"" + lines[1] + "\"");
        }
        CobbieTestSupport.command(server, "cobbie status");
        CobbieTestSupport.elapsed(server, 5);
        boolean shown = false;
        synchronized (HEARD) {
            for (String heard : HEARD) {
                if (heard.contains("Food:") && heard.contains("/20")) shown = true;
            }
        }
        if (!shown) {
            throw new AssertionError("/cobbie status never printed his food: the client heard "
                    + (HEARD.isEmpty() ? "nothing" : String.join(" | ", HEARD)));
        }
        System.out.println("[gametest] hunger: \"" + line + "\" on the panel and in /cobbie status");
    }

    private static FoodProperties foodOf(net.minecraft.world.item.Item item) {
        FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
        if (food == null) throw new AssertionError(item + " has no FOOD component in this game");
        return food;
    }
}
