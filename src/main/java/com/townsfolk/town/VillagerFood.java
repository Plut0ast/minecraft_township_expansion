package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerEquipmentContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Nitwits (and so Sentries) have a dedicated food slot, separate from their inventory, so it never
 * touches vanilla breeding. Below half health they eat from it: the food goes into their hand and
 * they eat it like a player (sound, crumbs), then get back what they were holding. Other villagers
 * have no food slot and never eat.
 */
public final class VillagerFood {
	/** Check whether to eat this often. */
	private static final int EAT_INTERVAL_TICKS = 100;
	/** Eat when below this share of health. */
	private static final float LOW_HEALTH = 0.5F;

	/**
	 * What the villager held before it started eating, whether that was a player's (or code's) item, and
	 * how much the food heals (the food itself is used up by the time the meal ends).
	 */
	public record Meal(ItemStack previousHand, boolean handOwned, int nutrition) {
		public static final Codec<Meal> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.OPTIONAL_CODEC.optionalFieldOf("previous_hand", ItemStack.EMPTY).forGetter(Meal::previousHand),
			Codec.BOOL.optionalFieldOf("hand_owned", false).forGetter(Meal::handOwned),
			Codec.INT.optionalFieldOf("nutrition", 4).forGetter(Meal::nutrition)
		).apply(i, Meal::new));
	}

	private VillagerFood() {
	}

	/** Nitwits (Sentries included) have a food slot; other villagers do not. */
	public static boolean hasFoodSlot(Villager villager) {
		return Sentry.canBecomeSentry(villager);
	}

	public static boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}

	public static ItemStack get(Villager villager) {
		return villager.getAttachedOrElse(ModRegistry.FOOD, ItemStack.EMPTY);
	}

	public static void set(Villager villager, ItemStack stack) {
		if (stack.isEmpty()) {
			villager.removeAttached(ModRegistry.FOOD);
		} else {
			villager.setAttached(ModRegistry.FOOD, stack);
		}
	}

	/** Anyone may feed a villager except Enemies of its town. */
	public static boolean canFeed(Player player, Villager villager) {
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town == null || !(villager.level() instanceof ServerLevel level)) {
			return true;
		}
		int reputation = TownReputation.get(level).get(town, player.getUUID());
		return TownReputation.Rank.of(reputation) != TownReputation.Rank.ENEMY;
	}

	/**
	 * A villager that dies mid-meal drops what it had put away to eat (a weapon a player gave it),
	 * which otherwise only exists in the meal record. The food in its hand drops as usual.
	 */
	public static void onDeath(Villager villager, ServerLevel level) {
		Meal meal = villager.getAttached(ModRegistry.MEAL);
		if (meal != null && meal.handOwned() && !meal.previousHand().isEmpty()) {
			villager.spawnAtLocation(level, meal.previousHand().copy());
		}
		villager.removeAttached(ModRegistry.MEAL);
	}

	/** True while the villager is eating from its hand. */
	public static boolean isEating(Villager villager) {
		return villager.getAttached(ModRegistry.MEAL) != null;
	}

	/** What the villager will hold again once it has eaten (its hand item otherwise). */
	public static ItemStack heldOutsideMeals(Villager villager) {
		Meal meal = villager.getAttached(ModRegistry.MEAL);
		return meal != null ? meal.previousHand() : villager.getMainHandItem();
	}

	/** Runs right after the villager's brain each tick (when nothing more important is steering it). */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		if (isEating(villager)) {
			tickMeal(villager, level);
			return;
		}
		if (hasFoodSlot(villager) && (level.getGameTime() + villager.getId()) % EAT_INTERVAL_TICKS == 0
			&& villager.getHealth() < villager.getMaxHealth() * LOW_HEALTH && !villager.isSleeping()) {
			startMeal(villager);
		}
	}

	/** Puts what the villager holds away, takes one piece of food from the food slot into its hand and starts eating. */
	private static void startMeal(Villager villager) {
		ItemStack slot = get(villager);
		if (!isFood(slot)) {
			return;
		}
		ItemStack bite = slot.copyWithCount(1);
		set(villager, slot.copyWithCount(slot.getCount() - 1));
		ItemStack previous = villager.getMainHandItem().copy();
		boolean owned = villager.getAttachedOrElse(ModRegistry.HAND_OWNED, false);
		villager.setAttached(ModRegistry.MEAL, new Meal(previous, owned, bite.get(DataComponents.FOOD).nutrition()));
		VillagerEquipmentContainer.setHand(villager, bite);
		villager.startUsingItem(InteractionHand.MAIN_HAND);
	}

	/**
	 * Vanilla finishes the bite itself (using up the food, leaving a bowl for stew). Once it stops:
	 * heal if the food was eaten, put it back in the food slot if the meal was cut short, then give
	 * the villager back what it held before.
	 */
	private static void tickMeal(Villager villager, ServerLevel level) {
		if (villager.isUsingItem()) {
			return;
		}
		Meal meal = villager.getAttached(ModRegistry.MEAL);
		villager.removeAttached(ModRegistry.MEAL);
		ItemStack hand = villager.getMainHandItem();
		FoodProperties properties = hand.get(DataComponents.FOOD);
		if (properties != null) {
			// Not eaten (interrupted, or the world was saved mid-bite): back into the food slot.
			ItemStack slot = get(villager);
			if (slot.isEmpty()) {
				set(villager, hand.copy());
			} else if (ItemStack.isSameItemSameComponents(slot, hand) && slot.getCount() < slot.getMaxStackSize()) {
				set(villager, slot.copyWithCount(slot.getCount() + hand.getCount()));
			} else {
				villager.spawnAtLocation(level, hand.copy());
			}
		} else {
			villager.heal(meal == null ? 4 : meal.nutrition());
			if (!hand.isEmpty()) {
				// Leftovers such as a bowl are dropped (pockets are left alone: they are vanilla's).
				villager.spawnAtLocation(level, hand.copy());
			}
		}
		restoreHand(villager, meal);
	}

	private static void restoreHand(Villager villager, @Nullable Meal meal) {
		ItemStack previous = meal == null ? ItemStack.EMPTY : meal.previousHand();
		if (meal != null && meal.handOwned()) {
			VillagerEquipmentContainer.setHand(villager, previous);
			return;
		}
		VillagerEquipmentContainer.WRITING.set(true);
		try {
			villager.setItemSlot(EquipmentSlot.MAINHAND, previous);
		} finally {
			VillagerEquipmentContainer.WRITING.set(false);
		}
		villager.removeAttached(ModRegistry.HAND_OWNED);
	}
}
