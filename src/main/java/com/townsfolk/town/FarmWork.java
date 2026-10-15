package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerEquipmentContainer;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Farming order for farmers: during the day, harvest ripe crops around where the order was given,
 * replant them, take the harvest to the closest chest (or barrel), and fetch seeds from it when out.
 * <p>
 * The harvest goes into the farmer's hand first, then its pockets. Only crops and seeds are taken to
 * the chest, so the farmer keeps any bread it carries.
 */
public final class FarmWork {
	/** How far from where the order was given the farmer looks for farmland. */
	private static final int FARM_RADIUS = 12;
	private static final int FARM_HEIGHT = 3;
	/** How far the farmer will walk to a chest. */
	private static final int CHEST_RADIUS = 24;
	/** Seeds kept for replanting when depositing; seeds fetched from the chest when out. */
	private static final int SEEDS_TO_KEEP = 16;
	private static final double REACH = 1.6;
	private static final float WALK_SPEED = 0.5F;
	private static final int THINK_INTERVAL_TICKS = 20;

	private enum Action { HARVEST, PLANT, DEPOSIT, FETCH_SEEDS }

	private record Task(Action action, BlockPos pos) {
	}

	private static final Map<Villager, Task> TASKS = new WeakHashMap<>();

	private FarmWork() {
	}

	/** Farmers use all of their pockets. */
	public static int capacity(ServerLevel level, Villager villager) {
		return villager.getInventory().getContainerSize();
	}

	public static boolean isFarmer(Villager villager) {
		return villager.getVillagerData().profession().is(VillagerProfession.FARMER) && !villager.isBaby();
	}

	/** Called when a villager is given the Farming order: farm around where it stands now. */
	public static void start(Villager villager) {
		villager.setAttached(ModRegistry.FARM_ANCHOR, villager.blockPosition());
		TASKS.remove(villager);
	}

	public static void stop(Villager villager) {
		villager.removeAttached(ModRegistry.FARM_ANCHOR);
		TASKS.remove(villager);
	}

	/** Runs right after the villager's brain each tick while it has the Farming order. */
	public static void tick(Villager villager, ServerLevel level) {
		BlockPos anchor = villager.getAttached(ModRegistry.FARM_ANCHOR);
		if (anchor == null || !isFarmer(villager)) {
			// Lost its job (or the order is stale): back to normal life.
			villager.removeAttached(ModRegistry.COMMAND);
			villager.removeAttached(ModRegistry.COMMAND_OWNER);
			stop(villager);
			return;
		}
		if (villager.isSleeping() || !level.isBrightOutside()) {
			TASKS.remove(villager);
			return; // farm by day, home by night
		}

		Task task = TASKS.get(villager);
		if (task == null || !isStillValid(level, villager, task)) {
			if ((level.getGameTime() + villager.getId()) % THINK_INTERVAL_TICKS != 0) {
				return;
			}
			task = chooseTask(level, villager, anchor);
			if (task == null) {
				TASKS.remove(villager);
				return;
			}
			TASKS.put(villager, task);
		}

		Vec3 target = Vec3.atBottomCenterOf(task.pos());
		if (villager.position().distanceTo(target) > REACH + (isContainerTask(task) ? 0.6 : 0.0)) {
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new BlockPosTracker(task.pos()), WALK_SPEED, 1));
			villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(task.pos()));
			return;
		}

		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(task.pos()));
		switch (task.action()) {
			case HARVEST -> harvest(level, villager, task.pos());
			case PLANT -> plant(level, villager, task.pos());
			case DEPOSIT -> deposit(level, villager, task.pos());
			case FETCH_SEEDS -> fetchSeeds(level, villager, task.pos());
		}
		TASKS.remove(villager);
	}

	private static boolean isContainerTask(Task task) {
		return task.action() == Action.DEPOSIT || task.action() == Action.FETCH_SEEDS;
	}

	private static boolean isStillValid(ServerLevel level, Villager villager, Task task) {
		return switch (task.action()) {
			case HARVEST -> isRipe(level.getBlockState(task.pos()));
			case PLANT -> isEmptyFarmland(level, task.pos()) && hasSeeds(villager);
			case DEPOSIT, FETCH_SEEDS -> HopperBlockEntity.getContainerAt(level, task.pos()) != null;
		};
	}

	/** Priorities: unload when full, harvest, plant (fetching seeds if out), unload leftovers. */
	private static @Nullable Task chooseTask(ServerLevel level, Villager villager, BlockPos anchor) {
		SimpleContainer inventory = villager.getInventory();
		int capacity = capacity(level, villager);
		BlockPos chest = closestChest(level, villager.blockPosition());

		if (chest != null && isFull(villager, capacity)) {
			return new Task(Action.DEPOSIT, chest);
		}
		BlockPos ripe = null;
		BlockPos empty = null;
		double ripeDist = Double.MAX_VALUE;
		double emptyDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(
			anchor.offset(-FARM_RADIUS, -FARM_HEIGHT, -FARM_RADIUS), anchor.offset(FARM_RADIUS, FARM_HEIGHT, FARM_RADIUS))) {
			double dist = pos.distSqr(villager.blockPosition());
			if (dist < ripeDist && isRipe(level.getBlockState(pos))) {
				ripe = pos.immutable();
				ripeDist = dist;
			} else if (dist < emptyDist && isEmptyFarmland(level, pos)) {
				empty = pos.immutable();
				emptyDist = dist;
			}
		}
		if (ripe != null && !isFull(villager, capacity)) {
			return new Task(Action.HARVEST, ripe);
		}
		if (ripe != null) {
			return chest != null ? new Task(Action.DEPOSIT, chest) : null;
		}
		if (empty != null) {
			if (hasSeeds(villager)) {
				return new Task(Action.PLANT, empty);
			}
			if (chest != null && chestHasSeeds(level, chest)) {
				return new Task(Action.FETCH_SEEDS, chest);
			}
		}
		if (chest != null && hasHarvest(villager)) {
			return new Task(Action.DEPOSIT, chest);
		}
		return null;
	}

	private static boolean isRipe(BlockState state) {
		return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
	}

	/** Air above farmland: somewhere to plant. */
	private static boolean isEmptyFarmland(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).getBlock() instanceof FarmlandBlock;
	}

	private static boolean isSeed(ItemStack stack) {
		return stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) && stack.getItem() instanceof BlockItem;
	}

	/** What a farm gives: crops and seeds. Only these are put in the hand or taken to the chest from it. */
	private static boolean isProduce(ItemStack stack) {
		return isSeed(stack) || stack.is(Items.WHEAT) || stack.is(Items.BEETROOT) || stack.is(Items.POISONOUS_POTATO);
	}

	/** The hand can hold the harvest when it is empty or already holds produce (not something a player gave). */
	private static boolean handHoldsProduce(Villager villager) {
		ItemStack hand = villager.getMainHandItem();
		return hand.isEmpty() || isProduce(hand);
	}

	private static boolean hasSeeds(Villager villager) {
		if (isSeed(villager.getMainHandItem())) {
			return true;
		}
		SimpleContainer inventory = villager.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (isSeed(inventory.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	/** Anything other than seeds (which are kept for replanting). */
	private static boolean hasHarvest(Villager villager) {
		ItemStack hand = villager.getMainHandItem();
		if (isProduce(hand) && !isSeed(hand)) {
			return true;
		}
		SimpleContainer inventory = villager.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (isProduce(stack) && !isSeed(stack)) {
				return true;
			}
		}
		return false;
	}

	/** No room left: the hand is full (or holds a player's item) and every pocket slot it may use is taken. */
	private static boolean isFull(Villager villager, int capacity) {
		ItemStack hand = villager.getMainHandItem();
		if (hand.isEmpty() || (isProduce(hand) && hand.getCount() < hand.getMaxStackSize())) {
			return false;
		}
		SimpleContainer inventory = villager.getInventory();
		for (int i = 0; i < Math.min(capacity, inventory.getContainerSize()); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
				return false;
			}
		}
		return true;
	}

	/** Hand first, then the first {@code capacity} pocket slots. Returns what did not fit. */
	private static ItemStack carry(Villager villager, ItemStack stack, int capacity) {
		ItemStack hand = villager.getMainHandItem();
		if (handHoldsProduce(villager)) {
			if (hand.isEmpty()) {
				VillagerEquipmentContainer.setHand(villager, stack.copy());
				return ItemStack.EMPTY;
			}
			if (ItemStack.isSameItemSameComponents(hand, stack) && hand.getCount() < hand.getMaxStackSize()) {
				int moved = Math.min(stack.getCount(), hand.getMaxStackSize() - hand.getCount());
				VillagerEquipmentContainer.setHand(villager, hand.copyWithCount(hand.getCount() + moved));
				stack = stack.copyWithCount(stack.getCount() - moved);
				if (stack.isEmpty()) {
					return ItemStack.EMPTY;
				}
			}
		}
		SimpleContainer inventory = villager.getInventory();
		int slots = Math.min(capacity, inventory.getContainerSize());
		for (int i = 0; i < slots && !stack.isEmpty(); i++) {
			ItemStack slot = inventory.getItem(i);
			if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				int moved = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
				slot.grow(moved);
				stack = stack.copyWithCount(stack.getCount() - moved);
			}
		}
		for (int i = 0; i < slots && !stack.isEmpty(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				inventory.setItem(i, stack);
				stack = ItemStack.EMPTY;
			}
		}
		inventory.setChanged();
		return stack;
	}

	private static void harvest(ServerLevel level, Villager villager, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!isRipe(state)) {
			return;
		}
		int capacity = capacity(level, villager);
		for (ItemStack drop : Block.getDrops(state, level, pos, null, villager, ItemStack.EMPTY)) {
			ItemStack left = carry(villager, drop, capacity);
			if (!left.isEmpty()) {
				Block.popResource(level, pos, left);
			}
		}
		level.destroyBlock(pos, false, villager);
		plant(level, villager, pos);
	}

	private static void plant(ServerLevel level, Villager villager, BlockPos pos) {
		if (!isEmptyFarmland(level, pos)) {
			return;
		}
		SimpleContainer inventory = villager.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (isSeed(stack) && stack.getItem() instanceof BlockItem seed) {
				placeCrop(level, pos, seed);
				stack.shrink(1);
				inventory.setChanged();
				return;
			}
		}
		ItemStack hand = villager.getMainHandItem();
		if (isSeed(hand) && hand.getItem() instanceof BlockItem seed) {
			placeCrop(level, pos, seed);
			VillagerEquipmentContainer.setHand(villager, hand.copyWithCount(hand.getCount() - 1));
		}
	}

	private static void placeCrop(ServerLevel level, BlockPos pos, BlockItem seed) {
		level.setBlockAndUpdate(pos, seed.getBlock().defaultBlockState());
		level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
	}

	/** Puts the harvest (and any seeds beyond what is needed for replanting) into the chest. */
	private static void deposit(ServerLevel level, Villager villager, BlockPos chestPos) {
		Container chest = HopperBlockEntity.getContainerAt(level, chestPos);
		if (chest == null) {
			return;
		}
		SimpleContainer inventory = villager.getInventory();
		int seedsKept = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!isProduce(stack)) {
				continue; // bread and anything else the farmer carries stays with it
			}
			int keep = 0;
			if (isSeed(stack)) {
				keep = Math.min(stack.getCount(), SEEDS_TO_KEEP - seedsKept);
				seedsKept += keep;
			}
			int toStore = stack.getCount() - keep;
			if (toStore <= 0) {
				continue;
			}
			// Whatever does not fit (chest full) stays with the farmer.
			ItemStack left = HopperBlockEntity.addItem(null, chest, stack.copyWithCount(toStore), null);
			stack.shrink(toStore - left.getCount());
			if (stack.isEmpty()) {
				inventory.setItem(i, ItemStack.EMPTY);
			}
		}
		ItemStack hand = villager.getMainHandItem();
		if (isProduce(hand)) {
			int keep = isSeed(hand) ? Math.min(hand.getCount(), Math.max(0, SEEDS_TO_KEEP - seedsKept)) : 0;
			int toStore = hand.getCount() - keep;
			if (toStore > 0) {
				ItemStack left = HopperBlockEntity.addItem(null, chest, hand.copyWithCount(toStore), null);
				VillagerEquipmentContainer.setHand(villager, hand.copyWithCount(keep + left.getCount()));
			}
		}
		inventory.setChanged();
		chest.setChanged();
		chestSound(level, chestPos);
	}

	/** Takes up to {@link #SEEDS_TO_KEEP} plantable seeds out of the chest. */
	private static void fetchSeeds(ServerLevel level, Villager villager, BlockPos chestPos) {
		Container chest = HopperBlockEntity.getContainerAt(level, chestPos);
		if (chest == null) {
			return;
		}
		int wanted = SEEDS_TO_KEEP;
		for (int i = 0; i < chest.getContainerSize() && wanted > 0; i++) {
			ItemStack stack = chest.getItem(i);
			if (isSeed(stack)) {
				ItemStack taken = stack.split(Math.min(wanted, stack.getCount()));
				wanted -= taken.getCount();
				ItemStack left = carry(villager, taken, capacity(level, villager));
				if (!left.isEmpty()) {
					stack.grow(left.getCount());
					break;
				}
			}
		}
		chest.setChanged();
		chestSound(level, chestPos);
	}

	private static boolean chestHasSeeds(ServerLevel level, BlockPos chestPos) {
		Container chest = HopperBlockEntity.getContainerAt(level, chestPos);
		if (chest == null) {
			return false;
		}
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (isSeed(chest.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	private static void chestSound(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
		level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.1F);
	}

	/** The closest chest or barrel within reach, searched through nearby loaded chunks' block entities. */
	private static @Nullable BlockPos closestChest(ServerLevel level, BlockPos from) {
		BlockPos best = null;
		double bestDist = (double) CHEST_RADIUS * CHEST_RADIUS;
		int minCx = (from.getX() - CHEST_RADIUS) >> 4;
		int maxCx = (from.getX() + CHEST_RADIUS) >> 4;
		int minCz = (from.getZ() - CHEST_RADIUS) >> 4;
		int maxCz = (from.getZ() + CHEST_RADIUS) >> 4;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(cx, cz);
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity) {
						double dist = blockEntity.getBlockPos().distSqr(from);
						if (dist < bestDist) {
							best = blockEntity.getBlockPos();
							bestDist = dist;
						}
					}
				}
			}
		}
		return best;
	}
}
