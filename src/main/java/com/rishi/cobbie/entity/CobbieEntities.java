package com.rishi.cobbie.entity;

import com.rishi.cobbie.Cobbie;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Registers the Cobbie entity type. Called from Cobbie.onInitialize().
 */
public final class CobbieEntities {
	public static final Identifier COBBIE_ID = Identifier.fromNamespaceAndPath(Cobbie.MOD_ID, "cobbie");
	public static final ResourceKey<EntityType<?>> COBBIE_KEY =
			ResourceKey.create(BuiltInRegistries.ENTITY_TYPE.key(), COBBIE_ID);

	public static final EntityType<CobbieEntity> COBBIE = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			COBBIE_ID,
			FabricEntityType.Builder.<CobbieEntity>createMob(CobbieEntity::new, MobCategory.CREATURE,
					mob -> mob.defaultAttributes(CobbieEntity::createAttributes))
					.sized(0.6f, 1.2f)
					.clientTrackingRange(8)
					.build(COBBIE_KEY)
	);

	private CobbieEntities() {}

	public static void register() {
		// Registration happens via the static field above; this exists for clarity at the call site.
	}
}
