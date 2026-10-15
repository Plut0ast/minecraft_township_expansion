package com.townsfolk.work;

import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerEquipmentContainer;
import com.townsfolk.town.FarmWork;
import com.townsfolk.town.Sentry;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The smiths. Villagers of their town get their services for free, handed straight to them:
 * <ul>
 * <li>Armourers repair worn armour and give Sentries chainmail and iron armour.</li>
 * <li>Leatherworkers repair leather armour and give other villagers leather armour.</li>
 * <li>Weaponsmiths repair weapons and give working villagers iron tools (hoe, axe, shears).</li>
 * <li>Toolsmiths repair tools, and also repair damaged tools left in their chest for an emerald each.</li>
 * </ul>
 * Villagers with worn gear walk to the right smith themselves (see {@link #visitForRepair}).
 */
public final class SmithWork {
	/** Gear this worn out (60% used) gets taken for repair. */
	private static final float WORN = 0.6F;
	private static final double TOWN_RADIUS = 48.0;
	private static final int GIFT_COOLDOWN_TICKS = 600;
	private static final int CHEST_REPAIR_TICKS = 200;
	private static final int CHEST_RADIUS = 6;
	private static final long VISIT_TIME_LIMIT = 1200;

	private static final Item[] LEATHER = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
	/** What an armourer gives a Sentry: iron on top, chainmail below. */
	private static final Item[] SENTRY_ARMOR = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private record Visit(Villager smith, EquipmentSlot slot, long giveUpAt) {
	}

	private static final Map<Villager, Long> NEXT_GIFT = new WeakHashMap<>();
	private static final Map<Villager, Long> NEXT_CHEST_REPAIR = new WeakHashMap<>();
	private static final Map<Villager, Visit> VISITS = new WeakHashMap<>();

	private SmithWork() {
	}

	public static boolean isSmith(Villager villager) {
		return WorkHelper.is(villager, VillagerProfession.ARMORER) || WorkHelper.is(villager, VillagerProfession.LEATHERWORKER)
			|| WorkHelper.is(villager, VillagerProfession.WEAPONSMITH) || WorkHelper.is(villager, VillagerProfession.TOOLSMITH);
	}

	// ---- What each smith does at work ----

	public static void tick(Villager smith, ServerLevel level, BlockPos site) {
		if (WorkHelper.is(smith, VillagerProfession.TOOLSMITH)) {
			repairChestTools(smith, level, site);
			return;
		}
		if (level.getGameTime() < NEXT_GIFT.getOrDefault(smith, 0L)) {
			return;
		}
		Gift gift = findGift(smith, level);
		if (gift == null || !WorkHelper.walkTo(smith, gift.to(), WorkHelper.REACH)) {
			return;
		}
		NEXT_GIFT.put(smith, level.getGameTime() + GIFT_COOLDOWN_TICKS);
		if (gift.slot() == EquipmentSlot.MAINHAND) {
			VillagerEquipmentContainer.setHand(gift.to(), gift.item());
		} else {
			gift.to().setItemSlot(gift.slot(), gift.item());
			gift.to().setGuaranteedDrop(gift.slot());
		}
		level.playSound(null, smith.getX(), smith.getY(), smith.getZ(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
		WorkHelper.workSound(smith, level);
	}

	private record Gift(Villager to, EquipmentSlot slot, ItemStack item) {
	}

	/** The nearest villager of the town this smith can equip with something it is missing. */
	private static @Nullable Gift findGift(Villager smith, ServerLevel level) {
		BlockPos town = smith.getAttached(ModRegistry.TOWN);
		return level.getEntitiesOfClass(Villager.class, smith.getBoundingBox().inflate(TOWN_RADIUS),
				v -> v != smith && v.isAlive() && !v.isBaby() && (town == null || town.equals(v.getAttached(ModRegistry.TOWN))))
			.stream()
			.sorted(Comparator.comparingDouble(smith::distanceToSqr))
			.map(v -> giftFor(smith, v))
			.filter(java.util.Objects::nonNull)
			.findFirst().orElse(null);
	}

	private static @Nullable Gift giftFor(Villager smith, Villager to) {
		if (WorkHelper.is(smith, VillagerProfession.ARMORER) && Sentry.isSentry(to)) {
			for (int i = 0; i < ARMOR_SLOTS.length; i++) {
				if (to.getItemBySlot(ARMOR_SLOTS[i]).isEmpty()) {
					return new Gift(to, ARMOR_SLOTS[i], new ItemStack(SENTRY_ARMOR[i]));
				}
			}
		}
		if (WorkHelper.is(smith, VillagerProfession.LEATHERWORKER) && !Sentry.isSentry(to)) {
			for (int i = 0; i < ARMOR_SLOTS.length; i++) {
				if (to.getItemBySlot(ARMOR_SLOTS[i]).isEmpty()) {
					return new Gift(to, ARMOR_SLOTS[i], new ItemStack(LEATHER[i]));
				}
			}
		}
		if (WorkHelper.is(smith, VillagerProfession.WEAPONSMITH) && to.getMainHandItem().isEmpty()) {
			Item tool = toolFor(to);
			if (tool != null) {
				return new Gift(to, EquipmentSlot.MAINHAND, new ItemStack(tool));
			}
		}
		return null;
	}

	/** Iron tools for workers who would use them. */
	private static @Nullable Item toolFor(Villager villager) {
		if (FarmWork.isFarmer(villager)) {
			return Items.IRON_HOE;
		}
		if (ButcherWork.isButcher(villager)) {
			return Items.IRON_AXE;
		}
		if (ShepherdWork.isShepherd(villager)) {
			return Items.SHEARS;
		}
		return null;
	}

	/** Toolsmiths repair damaged tools left in their chest, for an emerald each (also in the chest). */
	private static void repairChestTools(Villager smith, ServerLevel level, BlockPos table) {
		if (level.getGameTime() < NEXT_CHEST_REPAIR.getOrDefault(smith, 0L)) {
			return;
		}
		BlockPos chestPos = WorkHelper.closestChest(level, table, CHEST_RADIUS);
		Container chest = WorkHelper.container(level, chestPos);
		if (chest == null) {
			NEXT_CHEST_REPAIR.put(smith, level.getGameTime() + CHEST_REPAIR_TICKS);
			return;
		}
		int toolSlot = -1;
		int emeraldSlot = -1;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (toolSlot < 0 && isTool(stack) && stack.isDamaged()) {
				toolSlot = i;
			}
			if (emeraldSlot < 0 && stack.is(Items.EMERALD)) {
				emeraldSlot = i;
			}
		}
		if (toolSlot < 0 || emeraldSlot < 0) {
			NEXT_CHEST_REPAIR.put(smith, level.getGameTime() + CHEST_REPAIR_TICKS);
			return;
		}
		if (!WorkHelper.walkTo(smith, chestPos, WorkHelper.REACH)) {
			return;
		}
		NEXT_CHEST_REPAIR.put(smith, level.getGameTime() + CHEST_REPAIR_TICKS);
		chest.getItem(toolSlot).setDamageValue(0);
		chest.getItem(emeraldSlot).shrink(1);
		chest.setChanged();
		level.playSound(null, chestPos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6F, 1.0F);
		WorkHelper.workSound(smith, level);
	}

	// ---- Villagers taking worn gear to a smith ----

	/**
	 * Called for every working villager: with a worn piece of gear it walks to the right smith of its
	 * town, which repairs it on the spot for free. Returns true while on the way.
	 */
	public static boolean visitForRepair(Villager villager, ServerLevel level) {
		Visit visit = VISITS.get(villager);
		if (visit != null && (!visit.smith().isAlive() || level.getGameTime() > visit.giveUpAt() || !isWorn(villager.getItemBySlot(visit.slot())))) {
			VISITS.remove(villager);
			visit = null;
		}
		if (visit == null) {
			if ((level.getGameTime() + villager.getId()) % 200 != 0) {
				return false;
			}
			visit = planVisit(villager, level);
			if (visit == null) {
				return false;
			}
			VISITS.put(villager, visit);
		}
		if (!WorkHelper.walkTo(villager, visit.smith(), WorkHelper.REACH)) {
			return true;
		}
		villager.getItemBySlot(visit.slot()).setDamageValue(0);
		level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.5F, 1.0F);
		WorkHelper.workSound(visit.smith(), level);
		VISITS.remove(villager);
		return false;
	}

	private static @Nullable Visit planVisit(Villager villager, ServerLevel level) {
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND}) {
			ItemStack stack = villager.getItemBySlot(slot);
			if (!isWorn(stack)) {
				continue;
			}
			ResourceKey<VillagerProfession> smithType = smithFor(stack, slot);
			Villager smith = smithType == null ? null : findSmith(villager, level, smithType);
			if (smith == null && smithType == VillagerProfession.LEATHERWORKER) {
				smith = findSmith(villager, level, VillagerProfession.ARMORER); // armourers mend leather too
			}
			if (smith != null && smith != villager) {
				return new Visit(smith, slot, level.getGameTime() + VISIT_TIME_LIMIT);
			}
		}
		return null;
	}

	private static boolean isWorn(ItemStack stack) {
		return stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() * WORN;
	}

	private static @Nullable ResourceKey<VillagerProfession> smithFor(ItemStack stack, EquipmentSlot slot) {
		if (slot != EquipmentSlot.MAINHAND) {
			for (Item leather : LEATHER) {
				if (stack.is(leather)) {
					return VillagerProfession.LEATHERWORKER;
				}
			}
			return VillagerProfession.ARMORER;
		}
		if (isWeapon(stack)) {
			return VillagerProfession.WEAPONSMITH;
		}
		if (isTool(stack)) {
			return VillagerProfession.TOOLSMITH;
		}
		return null;
	}

	public static boolean isWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT) || stack.is(Items.MACE)
			|| stack.is(ItemTags.SPEARS);
	}

	public static boolean isTool(ItemStack stack) {
		return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
			|| stack.is(Items.SHEARS) || stack.is(Items.FISHING_ROD) || stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.BRUSH);
	}

	private static @Nullable Villager findSmith(Villager villager, ServerLevel level, ResourceKey<VillagerProfession> type) {
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		return level.getEntitiesOfClass(Villager.class, villager.getBoundingBox().inflate(TOWN_RADIUS),
				v -> v.isAlive() && !v.isBaby() && WorkHelper.is(v, type) && (town == null || town.equals(v.getAttached(ModRegistry.TOWN))))
			.stream().min(Comparator.comparingDouble(villager::distanceToSqr)).orElse(null);
	}
}
