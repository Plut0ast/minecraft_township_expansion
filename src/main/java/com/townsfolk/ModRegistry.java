package com.townsfolk;

import com.mojang.serialization.Codec;
import com.townsfolk.block.TownCenterBlock;
import com.townsfolk.block.TownCenterBlockEntity;
import com.townsfolk.menu.RepairMenu;
import com.townsfolk.menu.TownCenterMenu;
import com.townsfolk.menu.VillagerMenu;
import com.townsfolk.town.TownExile;
import com.townsfolk.town.VillagerFood;
import com.townsfolk.town.VillagerCommands;
import com.townsfolk.work.ShepherdWork;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModRegistry {
	private static final ResourceKey<Block> TOWN_CENTER_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, Townsfolk.id("town_center"));
	private static final ResourceKey<Item> TOWN_CENTER_ITEM_KEY = ResourceKey.create(Registries.ITEM, Townsfolk.id("town_center"));

	public static final Block TOWN_CENTER = Registry.register(
		BuiltInRegistries.BLOCK,
		TOWN_CENTER_BLOCK_KEY,
		new TownCenterBlock(BlockBehaviour.Properties.of()
			.setId(TOWN_CENTER_BLOCK_KEY)
			.mapColor(MapColor.WOOD)
			.strength(2.5F)
			.sound(SoundType.WOOD))
	);

	public static final Item TOWN_CENTER_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		TOWN_CENTER_ITEM_KEY,
		new BlockItem(TOWN_CENTER, new Item.Properties().setId(TOWN_CENTER_ITEM_KEY).useBlockDescriptionPrefix())
	);

	public static final BlockEntityType<TownCenterBlockEntity> TOWN_CENTER_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Townsfolk.id("town_center"),
		new BlockEntityType<>(TownCenterBlockEntity::new, Set.of(TOWN_CENTER))
	);

	public static final ExtendedMenuType<TownCenterMenu, TownCenterMenu.Data> TOWN_CENTER_MENU = Registry.register(
		BuiltInRegistries.MENU,
		Townsfolk.id("town_center"),
		new ExtendedMenuType<>(TownCenterMenu::new, TownCenterMenu.Data.STREAM_CODEC)
	);

	public static final ExtendedMenuType<VillagerMenu, VillagerMenu.Data> VILLAGER_MENU = Registry.register(
		BuiltInRegistries.MENU,
		Townsfolk.id("villager"),
		new ExtendedMenuType<>(VillagerMenu::new, VillagerMenu.Data.STREAM_CODEC)
	);

	public static final ExtendedMenuType<RepairMenu, RepairMenu.Data> REPAIR_MENU = Registry.register(
		BuiltInRegistries.MENU,
		Townsfolk.id("repair"),
		new ExtendedMenuType<>(RepairMenu::new, RepairMenu.Data.STREAM_CODEC)
	);

	/** Position of the Town Center this villager or iron golem belongs to. Cleared only if that Town Center is broken. */
	public static final AttachmentType<BlockPos> TOWN = AttachmentRegistry.<BlockPos>builder()
		.persistent(BlockPos.CODEC)
		.buildAndRegister(Townsfolk.id("town"));

	/**
	 * The banner of the town this villager or player belongs to, worn as a cape. Worked out again by
	 * {@code TownCapes} every second, so it is not saved.
	 */
	public static final AttachmentType<ItemStack> CAPE = AttachmentRegistry.<ItemStack>builder()
		.syncWith(ItemStack.OPTIONAL_STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Townsfolk.id("cape"));

	/** True when the villager's hand item was given by a player, so villager AI must not replace it. */
	public static final AttachmentType<Boolean> HAND_OWNED = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all())
		.buildAndRegister(Townsfolk.id("hand_owned"));

	/** Current order from a Trusted player (wander when absent). */
	public static final AttachmentType<VillagerCommands.Command> COMMAND = AttachmentRegistry.<VillagerCommands.Command>builder()
		.persistent(VillagerCommands.Command.CODEC)
		.buildAndRegister(Townsfolk.id("command"));

	/** The player who gave the current order; followed when the order is follow. */
	public static final AttachmentType<UUID> COMMAND_OWNER = AttachmentRegistry.<UUID>builder()
		.persistent(UUIDUtil.CODEC)
		.buildAndRegister(Townsfolk.id("command_owner"));

	/** Set while a villager that just joined a town is walking to its Town Center: the game time to give up at. */
	public static final AttachmentType<Long> ARRIVAL_DEADLINE = AttachmentRegistry.<Long>builder()
		.persistent(Codec.LONG)
		.buildAndRegister(Townsfolk.id("arrival_deadline"));

	/** Set on a villager removed from its town: which town, until when it is barred, and where it is heading. */
	public static final AttachmentType<TownExile.Exile> EXILE = AttachmentRegistry.<TownExile.Exile>builder()
		.persistent(TownExile.Exile.CODEC)
		.buildAndRegister(Townsfolk.id("exile"));

	/** Where a farmer was given the Farming order; it farms the farmland around this spot. */
	public static final AttachmentType<BlockPos> FARM_ANCHOR = AttachmentRegistry.<BlockPos>builder()
		.persistent(BlockPos.CODEC)
		.buildAndRegister(Townsfolk.id("farm_anchor"));

	/** A villager's food slot: eaten to heal, refilled by players or from farmers. */
	public static final AttachmentType<ItemStack> FOOD = AttachmentRegistry.<ItemStack>builder()
		.persistent(ItemStack.OPTIONAL_CODEC)
		.buildAndRegister(Townsfolk.id("food"));

	/** Set once a villager has been given its starting bread, so it is never given twice. */
	public static final AttachmentType<Boolean> STARTING_FOOD_GIVEN = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.buildAndRegister(Townsfolk.id("starting_food_given"));

	/** A shepherd's pen (where to stand to shear, and its chest), set up the first time it works. */
	public static final AttachmentType<ShepherdWork.Pen> SHEPHERD_PEN = AttachmentRegistry.<ShepherdWork.Pen>builder()
		.persistent(ShepherdWork.Pen.CODEC)
		.buildAndRegister(Townsfolk.id("shepherd_pen"));

	/** On a sheep: the shepherd it belongs to. */
	public static final AttachmentType<UUID> FLOCK_OWNER = AttachmentRegistry.<UUID>builder()
		.persistent(UUIDUtil.CODEC)
		.buildAndRegister(Townsfolk.id("flock_owner"));

	/** Each player's standing (trading xp) with this villager. */
	public static final AttachmentType<Map<UUID, Integer>> STANDING = AttachmentRegistry.<Map<UUID, Integer>>builder()
		.persistent(Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT))
		.buildAndRegister(Townsfolk.id("standing"));

	/** The level (1-5) each of the villager's current offers unlocks at, in offer order. */
	public static final AttachmentType<List<Integer>> OFFER_TIERS = AttachmentRegistry.<List<Integer>>builder()
		.persistent(Codec.INT.listOf())
		.buildAndRegister(Townsfolk.id("offer_tiers"));

	/** Set while a villager is eating from its hand: what it was holding before, to give back after. */
	public static final AttachmentType<VillagerFood.Meal> MEAL = AttachmentRegistry.<VillagerFood.Meal>builder()
		.persistent(VillagerFood.Meal.CODEC)
		.buildAndRegister(Townsfolk.id("meal"));

	/** The Minecraft day the villager last rerolled its trades. */
	public static final AttachmentType<Long> LAST_ROLL_DAY = AttachmentRegistry.<Long>builder()
		.persistent(Codec.LONG)
		.buildAndRegister(Townsfolk.id("last_roll_day"));

	private ModRegistry() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(TOWN_CENTER_ITEM));
	}
}
