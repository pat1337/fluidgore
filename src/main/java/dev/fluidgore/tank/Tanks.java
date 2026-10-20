package dev.fluidgore.tank;

import dev.fluidgore.FluidGore;
import dev.fluidgore.net.TankBurstPayload;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * The Cobble Tank's registry entries (2.16): the one place Fluid Gore adds content to the game, asked for by the
 * user ("real damage applies for this block only"). A world with it needs Fluid Gore on the server and on every
 * client that joins.
 */
public final class Tanks {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "cobble_tank");
	/** A flying tank hitting a body. */
	public static final ResourceKey<DamageType> IMPACT = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "tank"));
	public static Block BLOCK;
	public static Item ITEM;
	public static BlockEntityType<TankBlockEntity> BLOCK_ENTITY;
	public static EntityType<TankEntity> ENTITY;

	private Tanks() {
	}

	public static void register() {
		BLOCK = Registry.register(BuiltInRegistries.BLOCK, ID, new TankBlock(BlockBehaviour.Properties.of()
				.setId(ResourceKey.create(Registries.BLOCK, ID))
				.mapColor(MapColor.STONE)
				// Hard to break when empty, and no explosion resistance: a blast always reaches it.
				.strength(3.5F, 0.0F)
				.sound(SoundType.STONE)
				.pushReaction(PushReaction.IMMOVEABLE)));
		ITEM = Registry.register(BuiltInRegistries.ITEM, ID, new BlockItem(BLOCK,
				new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix()));
		BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID, new BlockEntityType<>(TankBlockEntity::new, Set.of(BLOCK)));
		ENTITY = Registry.register(BuiltInRegistries.ENTITY_TYPE, ID, EntityType.Builder.<TankEntity>of(TankEntity::new, MobCategory.MISC)
				.noLootTable()
				.sized(0.98F, 0.98F)
				.clientTrackingRange(10)
				.updateInterval(1)
				.build(ResourceKey.create(Registries.ENTITY_TYPE, ID)));

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(output -> output.insertAfter(Items.TNT, ITEM));
		PayloadTypeRegistry.clientboundPlay().register(TankBurstPayload.TYPE, TankBurstPayload.CODEC);
		ServerTickEvents.END_LEVEL_TICK.register(TankBurst::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> TankBurst.clear());
	}
}
