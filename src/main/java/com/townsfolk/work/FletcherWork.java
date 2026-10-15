package com.townsfolk.work;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.Sentry;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Fletchers gather loose feathers, flint and sticks near their fletching table, craft arrows from the
 * chest beside it (vanilla's recipe: flint, stick and feather make four), and keep their town's
 * bow-armed Sentries stocked with arrows.
 */
public final class FletcherWork {
	private static final int CHEST_RADIUS = 6;
	private static final double GATHER_RADIUS = 12.0;
	private static final double SENTRY_RADIUS = 32.0;
	/** Sentries are topped up when below this many arrows, to this many. */
	private static final int SENTRY_LOW = 16;
	private static final int SENTRY_FULL = 32;
	private static final int CRAFT_TICKS = 100;

	private static final Map<Villager, BlockPos> CHESTS = new WeakHashMap<>();
	private static final Map<Villager, Long> NEXT_CRAFT = new WeakHashMap<>();

	private FletcherWork() {
	}

	public static boolean isFletcher(Villager villager) {
		return WorkHelper.is(villager, VillagerProfession.FLETCHER);
	}

	private static boolean isMaterial(ItemStack stack) {
		return stack.is(Items.FEATHER) || stack.is(Items.FLINT) || stack.is(Items.STICK);
	}

	public static void tick(Villager villager, ServerLevel level, BlockPos table) {
		BlockPos chestPos = chestFor(villager, level, table);
		Container chest = WorkHelper.container(level, chestPos);

		// 1. Bring arrows to a Sentry that is running low.
		Villager sentry = sentryNeedingArrows(villager, level);
		if (sentry != null) {
			if (WorkHelper.pocketsHave(villager, stack -> stack.is(ItemTags.ARROWS))) {
				if (WorkHelper.walkTo(villager, sentry, WorkHelper.REACH)) {
					giveArrows(villager, sentry, level);
				}
				return;
			}
			if (chest != null && countIn(chest, stack -> stack.is(ItemTags.ARROWS)) > 0) {
				if (WorkHelper.walkTo(villager, chestPos, WorkHelper.REACH)) {
					WorkHelper.take(villager, chest, stack -> stack.is(ItemTags.ARROWS), SENTRY_FULL);
				}
				return;
			}
		}

		// 2. Put arrows and gathered materials away.
		if (chest != null && WorkHelper.pocketsHave(villager, stack -> stack.is(ItemTags.ARROWS) || isMaterial(stack))
			&& (WorkHelper.pocketsFull(villager) || nearbyLoot(villager, level, table) == null)) {
			if (WorkHelper.walkTo(villager, chestPos, WorkHelper.REACH)) {
				WorkHelper.unload(villager, chest, stack -> stack.is(ItemTags.ARROWS) || isMaterial(stack));
				level.playSound(null, chestPos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
			}
			return;
		}

		// 3. Pick up loose feathers, flint and sticks lying about.
		ItemEntity loot = nearbyLoot(villager, level, table);
		if (loot != null) {
			if (WorkHelper.walkTo(villager, loot, 1.5)) {
				ItemStack left = WorkHelper.pocket(villager, loot.getItem().copy());
				if (left.isEmpty()) {
					loot.discard();
				} else {
					loot.setItem(left);
				}
			}
			return;
		}

		// 4. Craft arrows at the table from what is in the chest.
		if (chest != null && level.getGameTime() >= NEXT_CRAFT.getOrDefault(villager, 0L) && canCraft(chest)
			&& WorkHelper.walkTo(villager, table, WorkHelper.REACH)) {
			NEXT_CRAFT.put(villager, level.getGameTime() + CRAFT_TICKS);
			takeOne(chest, Items.FLINT);
			takeOne(chest, Items.STICK);
			takeOne(chest, Items.FEATHER);
			ItemStack left = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, chest, new ItemStack(Items.ARROW, 4), null);
			if (!left.isEmpty()) {
				WorkHelper.pocket(villager, left);
			}
			chest.setChanged();
			WorkHelper.workSound(villager, level);
		}
	}

	private static @Nullable BlockPos chestFor(Villager villager, ServerLevel level, BlockPos table) {
		if (!CHESTS.containsKey(villager) || (level.getGameTime() + villager.getId()) % 100 == 0) {
			CHESTS.put(villager, WorkHelper.closestChest(level, table, CHEST_RADIUS));
		}
		return CHESTS.get(villager);
	}

	private static @Nullable ItemEntity nearbyLoot(Villager villager, ServerLevel level, BlockPos table) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(table).inflate(GATHER_RADIUS), e -> isMaterial(e.getItem()))
			.stream().min(Comparator.comparingDouble(villager::distanceToSqr)).orElse(null);
	}

	/** A bow-armed Sentry of the same town nearby that is running low on arrows. */
	private static @Nullable Villager sentryNeedingArrows(Villager fletcher, ServerLevel level) {
		BlockPos town = fletcher.getAttached(ModRegistry.TOWN);
		return level.getEntitiesOfClass(Villager.class, fletcher.getBoundingBox().inflate(SENTRY_RADIUS),
				v -> Sentry.isSentry(v) && Sentry.isArcher(v) && Sentry.arrowCount(v) < SENTRY_LOW
					&& (town == null || town.equals(v.getAttached(ModRegistry.TOWN))))
			.stream().min(Comparator.comparingDouble(fletcher::distanceToSqr)).orElse(null);
	}

	private static void giveArrows(Villager fletcher, Villager sentry, ServerLevel level) {
		int wanted = SENTRY_FULL - Sentry.arrowCount(sentry);
		SimpleContainer pockets = fletcher.getInventory();
		for (int i = 0; i < pockets.getContainerSize() && wanted > 0; i++) {
			ItemStack stack = pockets.getItem(i);
			if (stack.is(ItemTags.ARROWS)) {
				ItemStack moving = stack.copyWithCount(Math.min(wanted, stack.getCount()));
				int moved = moving.getCount() - sentry.getInventory().addItem(moving).getCount();
				stack.shrink(moved);
				wanted -= moved;
			}
		}
		pockets.setChanged();
		WorkHelper.workSound(fletcher, level);
	}

	private static boolean canCraft(Container chest) {
		return countIn(chest, s -> s.is(Items.FLINT)) > 0 && countIn(chest, s -> s.is(Items.STICK)) > 0 && countIn(chest, s -> s.is(Items.FEATHER)) > 0;
	}

	private static int countIn(Container container, java.util.function.Predicate<ItemStack> filter) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (filter.test(container.getItem(i))) {
				count += container.getItem(i).getCount();
			}
		}
		return count;
	}

	private static void takeOne(Container container, net.minecraft.world.item.Item item) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (container.getItem(i).is(item)) {
				container.getItem(i).shrink(1);
				return;
			}
		}
	}
}
