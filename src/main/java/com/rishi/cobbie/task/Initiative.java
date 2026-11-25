package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A project he starts himself: something worth doing that he can see and reach.
 *
 * <p>This is milestone 8, and the whole design is that <b>a project is a plan</b>. It is chosen here from
 * what he can actually see, handed to the same {@link TaskRunner} every owner instruction goes through,
 * and therefore gets the same terminal states, retries, abandonment reason and panel row for free — the
 * alternative, a second mechanism beside the plan, is how autonomy turns into something nobody can
 * interrupt or explain.
 *
 * <p><b>Choosing costs no model call.</b> The candidate comes from perception, deterministically, so a
 * project cannot cost anything per block and cannot be invented out of nothing. The owner's instruction
 * remains the only source of arbitrary plans.
 *
 * <p>Three kinds. <b>Gather</b> and <b>flatten</b> are blocks he noticed worth taking; the third,
 * <b>a cairn</b>, is the one whose shape he decides — three blocks stacked on flat ground out of what he
 * is carrying, so "what he builds" is his choice and not only "which block".
 *
 * <p><b>Announced only when it can start.</b> A candidate that fails the same gate its own step
 * applies — no tool for the drop, nowhere to stand, no room for what comes out — is not counted, so a
 * project that could not take its first step is never promised out loud.
 *
 * <p><b>Safe by construction.</b> Targets sit within six blocks of his feet, between one below and two
 * above, are never a block he should not break (lava, fire, anything with a block entity — a chest is
 * somebody's storage), and the project is a handful of steps, so there is no route to a cliff, a lava
 * pool or a hostile he cannot see.
 */
public record Initiative() {

    /** How far he will look, how many blocks a project touches, and how many kinds he will consider. */
    private static final int RADIUS = 6;
    private static final int MAX_STEPS = 3;
    private static final int MIN_BLOCKS = 3;

    /** What he is willing to stack a cairn out of, in the order he would rather use it. */
    private static final List<String> CAIRN_MATERIALS = List.of(
            "minecraft:cobblestone", "minecraft:stone", "minecraft:oak_planks", "minecraft:dirt");

    /** What he has decided to do, and the sentence he says once about it. */
    public record Project(String request, String line, List<ActionIntent> steps) {}

    /**
     * Something worth doing here, or null when there is nothing he can see that is worth the trip.
     *
     * <p>Two kinds for now. <b>Gather</b>: three or more of one useful block — logs or stone — close
     * enough to walk to. <b>Flatten</b>: three or more single blocks of rubble standing on the ground he
     * is on, which is a small area worth clearing.
     *
     * <p>A third kind, repairing a damaged build, is deliberately absent: there is no honest way to tell
     * "damaged structure" from "the owner's build as intended" with what he perceives, and a made-up
     * detector would pick blocks out of somebody's house.
     */
    public static Project choose(CobbieEntity cobbie) {
        if (!(cobbie.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
        BlockPos feet = cobbie.blockPosition();

        Map<String, List<BlockPos>> gatherable = new LinkedHashMap<>();
        List<BlockPos> rubble = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-RADIUS, -1, -RADIUS),
                feet.offset(RADIUS, 2, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !breakable(level, pos, state)) continue;
            // A project is announced only if it can start: the same gate the step applies, never a
            // second opinion. Announcing and then discovering is the one shape to avoid.
            if (!cobbie.canStartMining(pos)) continue;
            if (state.is(BlockTags.LOGS) || state.is(Blocks.STONE)) {
                gatherable.computeIfAbsent(state.getBlock().getName().getString(), k -> new ArrayList<>())
                        .add(pos.immutable());
            } else if (isRubble(state) && pos.getY() == feet.getY() && level.getBlockState(pos.above()).isAir()) {
                rubble.add(pos.immutable());
            }
        }

        // Gather first: a pile of a useful block is the clearer project of the two.
        for (Map.Entry<String, List<BlockPos>> entry : gatherable.isEmpty() || !permits(ActionVocabulary.MINE)
                ? java.util.Collections.<Map.Entry<String, List<BlockPos>>>emptyList()
                : gatherable.entrySet()) {
            if (entry.getValue().size() < MIN_BLOCKS) continue;
            List<BlockPos> take = entry.getValue().subList(0, Math.min(MAX_STEPS, entry.getValue().size()));
            return new Project("gathering the " + entry.getKey().toLowerCase(java.util.Locale.ROOT) + " nearby",
                    "I'm going to gather that " + entry.getKey().toLowerCase(java.util.Locale.ROOT)
                            + " while we're standing here.",
                    mines(take));
        }
        Project build = cairn(cobbie, level, feet);
        if (build != null) return build;
        if (rubble.size() >= MIN_BLOCKS && permits(ActionVocabulary.MINE)) {
            return new Project("clearing the rubble around me",
                    "I'm going to clear this bit of rubble so the ground's tidy.",
                    mines(rubble.subList(0, Math.min(MAX_STEPS, rubble.size()))));
        }
        return null;
    }

    /**
     * Something he <b>decides the shape of</b>, rather than a block he noticed: a cairn, three blocks
     * stacked on flat ground out of material he is carrying. It is the first project whose content is a
     * decision, and it stays small enough to be honest about — a marker he can build in three steps,
     * not a structure he would have to survey a site for.
     */
    private static Project cairn(CobbieEntity cobbie, net.minecraft.server.level.ServerLevel level, BlockPos feet) {
        if (!permits(ActionVocabulary.PLACE)) return null;
        for (String material : CAIRN_MATERIALS) {
            if (cobbie.countOf(material) < MIN_BLOCKS) continue;
            // Every site gets a full try: one blocked spot — his own feet, the owner standing there —
            // must not cost him the project when the next spot over is clear.
            for (BlockPos site : flatSitesNear(level, feet)) {
                List<ActionIntent> steps = new ArrayList<>();
                for (int i = 0; i < MAX_STEPS && cobbie.countOf(material) > steps.size(); i++) {
                    BlockPos pos = site.above(i);
                    // The base needs real ground under it; each block above stands on the one his own
                    // plan has already promised to place.
                    boolean buildable = i == 0 ? cobbie.canPlace(material, pos) : cobbie.canPlaceOnOwn(material, pos);
                    if (!buildable) break;
                    steps.add(new ActionIntent(ActionVocabulary.PLACE, material,
                            pos.getX(), pos.getY(), pos.getZ(), ""));
                }
                if (steps.size() < MIN_BLOCKS) continue;
                String name = material.substring(material.indexOf(':') + 1).replace('_', ' ');
                return new Project("building a cairn of " + name + " here",
                        "I'm going to stack a little cairn of " + name + " so this spot's marked.",
                        steps);
            }
        }
        return null;
    }

    /** Flat ground within reach that nothing occupies and nobody he can see is standing on. */
    private static List<BlockPos> flatSitesNear(net.minecraft.server.level.ServerLevel level, BlockPos feet) {
        List<BlockPos> sites = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos site = feet.offset(dx, 0, dz);
                if (!level.isLoaded(site)) continue;
                if (level.getBlockState(site.below()).isAir()) continue;
                if (!level.getBlockState(site).canBeReplaced()) continue;
                if (crowded(level, site)) continue;
                sites.add(site);
            }
        }
        return sites;
    }

    /** Somebody standing on the spot, in any of the three blocks he would fill. */
    private static boolean crowded(net.minecraft.server.level.ServerLevel level, BlockPos site) {
        for (int i = 0; i < MAX_STEPS; i++) {
            BlockPos pos = site.above(i);
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(pos), e -> e.isAlive()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean permits(String verb) {
        return com.rishi.cobbie.config.CobbieConfig.permits(verb);
    }

    private static List<ActionIntent> mines(List<BlockPos> positions) {
        List<ActionIntent> steps = new ArrayList<>();
        for (BlockPos pos : positions) {
            steps.add(new ActionIntent(ActionVocabulary.MINE, "", pos.getX(), pos.getY(), pos.getZ(), ""));
        }
        return steps;
    }

    /**
     * Whether it is safe and sensible to break this block: not lava or fire, and not somebody's storage
     * or machinery, which is what carrying a block entity means.
     */
    private static boolean breakable(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock) return false;
        return level.getBlockEntity(pos) == null;
    }

    /** Loose ground standing on top of the surface: dirt, gravel, cobblestone and the like. */
    private static boolean isRubble(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.TUFF);
    }
}
