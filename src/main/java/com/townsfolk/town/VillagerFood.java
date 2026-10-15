package com.townsfolk.town;

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
import net.minecraft.world.SimpleContainer;
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
 * a farmer of its town, and farmers top up from their own harvest. Every villager starts with bread.
 */
public final class VillagerFood {
	/** Eat one item this often while hurt. */
	private static final int EAT_INTERVAL_TICKS = 100;
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

	private static final Map<Villager, Trip> TRIPS = new WeakHashMap<>();

	private VillagerFood() {
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
		if (villager.getAttachedOrElse(ModRegistry.STARTING_FOOD_GIVEN, false)) {
			return;
		}
		villager.setAttached(ModRegistry.STARTING_FOOD_GIVEN, true);
		if (get(villager).isEmpty()) {
			set(villager, new ItemStack(Items.BREAD, 3 + villager.getRandom().nextInt(4)));
		}
	}

	/** Runs right after the villager's brain each tick (when nothing more important is steering it). */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		long time = level.getGameTime() + villager.getId();
		if (time % EAT_INTERVAL_TICKS == 0 && villager.getHealth() < villager.getMaxHealth()) {
			eat(villager, level);
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

	private static void eat(Villager villager, ServerLevel level) {
		ItemStack food = get(villager);
		FoodProperties properties = food.get(DataComponents.FOOD);
		if (properties == null) {
			return;
		}
		villager.heal(properties.nutrition());
		level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food.getItem()),
			villager.getX(), villager.getEyeY() - 0.2, villager.getZ(), 8, 0.15, 0.1, 0.15, 0.05);
		level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6F, 1.0F);
		set(villager, food.copyWithCount(food.getCount() - 1));
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
		for (Villager other : level.getEntitiesOfClass(Villager.class, area, v -> v != villager && v.isAlive() && FarmWork.isFarmer(v))) {
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
