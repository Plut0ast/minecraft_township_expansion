package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.TownsfolkConfig;
import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerEquipmentContainer;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Each villager's food slot. A hurt villager eats from it to heal (vanilla villagers never heal on
 * their own). Players who are not Enemies can fill it; when it runs low the villager fetches food from
 * a farmer or butcher of its town, and farmers top up from their own harvest. Every villager starts with bread.
 * <p>
 * Eating is done properly: food in the pockets moves into the empty food slot, one piece moves from
 * there into the hand, and the villager eats it like a player (sound, crumbs, about 1.6 seconds). What
 * it held before (a Sentry's weapon, a farmer's harvest) is put away meanwhile and given back after.
 */
public final class VillagerFood {
	/** Check whether to eat this often. */
	private static final int EAT_INTERVAL_TICKS = 100;
	/** Villagers eat when below this share of their health. */
	private static final float LOW_HEALTH = 0.5F;
	/** Go and get more when down to this many. */
	private static final int LOW = 3;
	/** Take this many from a farmer at a time. */
	private static final int RESTOCK = 8;
	private static final double FARMER_SEARCH_RADIUS = 32.0;
	private static final double HAND_OVER_DISTANCE = 2.5;
	private static final int SEARCH_INTERVAL_TICKS = 100;
	/** Give up on a trip to a farmer after this long (1 minute). */
	private static final long TRIP_TIME_LIMIT = 1200;
	private static final float WALK_SPEED = 0.55F;

	private record Trip(Villager farmer, long giveUpAt) {
	}

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

	private static final Map<Villager, Trip> TRIPS = new WeakHashMap<>();

	private VillagerFood() {
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

	/** True while the villager is on its way to a farmer (or butcher) for food. */
	public static boolean isFetchingFood(Villager villager) {
		return TRIPS.containsKey(villager);
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

	/** Every villager starts out with a little bread in its food slot, once. */
	public static void giveStartingFood(Villager villager) {
		if (!TownsfolkConfig.villagerFood || villager.getAttachedOrElse(ModRegistry.STARTING_FOOD_GIVEN, false)) {
			return;
		}
		villager.setAttached(ModRegistry.STARTING_FOOD_GIVEN, true);
		if (get(villager).isEmpty()) {
			set(villager, new ItemStack(Items.BREAD, 3 + villager.getRandom().nextInt(4)));
		}
	}

	/** Runs right after the villager's brain each tick (when nothing more important is steering it). */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		if (isEating(villager)) {
			tickMeal(villager);
			return;
		}
		long time = level.getGameTime() + villager.getId();
		if (TownsfolkConfig.villagerFood) {
			refillFromPockets(villager);
		}
		// Eating from the pockets works whether or not the food slot is turned on.
		if (time % EAT_INTERVAL_TICKS == 0 && villager.getHealth() < villager.getMaxHealth() * LOW_HEALTH && !villager.isSleeping()) {
			startMeal(villager);
		}
		if (!TownsfolkConfig.villagerFood) {
			TRIPS.remove(villager);
			return;
		}
		if (get(villager).getCount() > LOW) {
			TRIPS.remove(villager);
			return;
		}
		if (FarmWork.isFarmer(villager)) {
			takeFrom(villager, villager); // farmers snack on their own harvest
			return;
		}
		fetchFromFarmer(villager, level, time);
	}

	/** An empty food slot is filled from food the villager carries in its pockets. */
	private static void refillFromPockets(Villager villager) {
		if (!get(villager).isEmpty()) {
			return;
		}
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			ItemStack stack = pockets.getItem(i);
			if (isFood(stack)) {
				set(villager, stack.copy());
				pockets.setItem(i, ItemStack.EMPTY);
				return;
			}
		}
	}

	/** Puts what the villager holds away, takes one piece of food from the food slot into its hand and starts eating. */
	private static void startMeal(Villager villager) {
		ItemStack bite = takeOneFood(villager);
		if (bite.isEmpty()) {
			return;
		}
		ItemStack previous = villager.getMainHandItem().copy();
		boolean owned = villager.getAttachedOrElse(ModRegistry.HAND_OWNED, false);
		villager.setAttached(ModRegistry.MEAL, new Meal(previous, owned, bite.get(DataComponents.FOOD).nutrition()));
		VillagerEquipmentContainer.setHand(villager, bite);
		villager.startUsingItem(InteractionHand.MAIN_HAND);
	}

	/** One piece of food from the food slot (when it is turned on) or else the pockets; empty if there is none. */
	private static ItemStack takeOneFood(Villager villager) {
		ItemStack slot = get(villager);
		if (TownsfolkConfig.villagerFood && isFood(slot)) {
			set(villager, slot.copyWithCount(slot.getCount() - 1));
			return slot.copyWithCount(1);
		}
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			ItemStack stack = pockets.getItem(i);
			if (isFood(stack)) {
				ItemStack one = stack.split(1);
				pockets.setChanged();
				return one;
			}
		}
		return ItemStack.EMPTY;
	}

	/**
	 * Vanilla finishes the bite itself (using up the food, leaving a bowl for stew). Once it stops:
	 * heal if the food was eaten, put it back in the food slot if the meal was cut short, then give
	 * the villager back what it held before.
	 */
	private static void tickMeal(Villager villager) {
		if (villager.isUsingItem()) {
			return;
		}
		Meal meal = villager.getAttached(ModRegistry.MEAL);
		villager.removeAttached(ModRegistry.MEAL);
		ItemStack hand = villager.getMainHandItem();
		FoodProperties properties = hand.get(DataComponents.FOOD);
		if (properties != null) {
			// Not eaten (interrupted, or the world was saved mid-bite): back where it came from.
			ItemStack slot = get(villager);
			if (!TownsfolkConfig.villagerFood) {
				ItemStack rest = villager.getInventory().addItem(hand.copy());
				if (!rest.isEmpty()) {
					villager.spawnAtLocation((ServerLevel) villager.level(), rest);
				}
			} else if (slot.isEmpty()) {
				set(villager, hand.copy());
			} else if (ItemStack.isSameItemSameComponents(slot, hand) && slot.getCount() < slot.getMaxStackSize()) {
				set(villager, slot.copyWithCount(slot.getCount() + hand.getCount()));
			} else {
				villager.spawnAtLocation((ServerLevel) villager.level(), hand.copy());
			}
		} else {
			villager.heal(meal == null ? 4 : meal.nutrition());
			if (!hand.isEmpty()) {
				// Leftovers such as a bowl go in the pockets, or on the ground if they are full.
				ItemStack rest = villager.getInventory().addItem(hand.copy());
				if (!rest.isEmpty()) {
					villager.spawnAtLocation((ServerLevel) villager.level(), rest);
				}
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


	private static void fetchFromFarmer(Villager villager, ServerLevel level, long time) {
		boolean busy = villager.isSleeping() || villager.isBaby() || !level.isBrightOutside()
			|| VillagerCommands.commandOf(villager) != VillagerCommands.Command.WANDER
			|| villager.getAttached(ModRegistry.ARRIVAL_DEADLINE) != null
			|| villager.getAttached(ModRegistry.EXILE) != null;
		if (busy) {
			TRIPS.remove(villager);
			return;
		}

		Trip trip = TRIPS.get(villager);
		if (trip != null && (!trip.farmer().isAlive() || level.getGameTime() > trip.giveUpAt() || !hasFoodToGive(trip.farmer()))) {
			TRIPS.remove(villager);
			trip = null;
		}
		if (trip == null) {
			if (time % SEARCH_INTERVAL_TICKS != 0) {
				return;
			}
			Villager farmer = findFarmer(villager, level);
			if (farmer == null) {
				return;
			}
			trip = new Trip(farmer, level.getGameTime() + TRIP_TIME_LIMIT);
			TRIPS.put(villager, trip);
		}

		Villager farmer = trip.farmer();
		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(farmer, true));
		if (villager.distanceTo(farmer) > HAND_OVER_DISTANCE) {
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(farmer, WALK_SPEED, 1));
			return;
		}
		takeFrom(villager, farmer);
		level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
		TRIPS.remove(villager);
	}

	/** The nearest farmer of the same town carrying something to eat. */
	private static @Nullable Villager findFarmer(Villager villager, ServerLevel level) {
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		AABB area = villager.getBoundingBox().inflate(FARMER_SEARCH_RADIUS);
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager other : level.getEntitiesOfClass(Villager.class, area, v -> v != villager && v.isAlive() && isFoodSupplier(v))) {
			if (town != null && !town.equals(other.getAttached(ModRegistry.TOWN))) {
				continue;
			}
			double dist = villager.distanceToSqr(other);
			if (dist < bestDist && hasFoodToGive(other)) {
				best = other;
				bestDist = dist;
			}
		}
		return best;
	}

	/** Farmers grow food and butchers cook it; both share what they carry. */
	private static boolean isFoodSupplier(Villager villager) {
		return FarmWork.isFarmer(villager) || villager.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.BUTCHER);
	}

	/** Food a farmer is carrying in its hand or pockets (not its own food slot). */
	private static boolean hasFoodToGive(Villager farmer) {
		if (isFood(farmer.getMainHandItem())) {
			return true;
		}
		SimpleContainer inventory = farmer.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (isFood(inventory.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	/** Moves up to {@link #RESTOCK} food from the giver's pockets (then hand) into the villager's food slot. */
	private static void takeFrom(Villager villager, Villager giver) {
		ItemStack slot = get(villager);
		int wanted = Math.min(RESTOCK, (slot.isEmpty() ? 64 : slot.getMaxStackSize()) - slot.getCount());
		SimpleContainer inventory = giver.getInventory();
		for (int i = 0; i < inventory.getContainerSize() && wanted > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (isFood(stack) && (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, stack))) {
				int moved = Math.min(wanted, stack.getCount());
				slot = slot.isEmpty() ? stack.copyWithCount(moved) : slot.copyWithCount(slot.getCount() + moved);
				stack.shrink(moved);
				wanted -= moved;
			}
		}
		inventory.setChanged();
		ItemStack hand = giver.getMainHandItem();
		if (wanted > 0 && isFood(hand) && (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, hand))) {
			int moved = Math.min(wanted, hand.getCount());
			slot = slot.isEmpty() ? hand.copyWithCount(moved) : slot.copyWithCount(slot.getCount() + moved);
			VillagerEquipmentContainer.setHand(giver, hand.copyWithCount(hand.getCount() - moved));
		}
		set(villager, slot);
	}
}
