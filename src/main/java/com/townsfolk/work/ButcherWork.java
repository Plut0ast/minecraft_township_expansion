package com.townsfolk.work;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.VillagerFood;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Butchers take raw meat (and fish) from the chest by their smoker, smoke it, and hand the cooked food
 * to hungry villagers of their town; anything left over goes back in the chest. Villagers running low
 * on food can also come to a butcher for it.
 */
public final class ButcherWork {
	private static final Map<Item, Item> COOKED = Map.of(
		Items.BEEF, Items.COOKED_BEEF,
		Items.PORKCHOP, Items.COOKED_PORKCHOP,
		Items.MUTTON, Items.COOKED_MUTTON,
		Items.CHICKEN, Items.COOKED_CHICKEN,
		Items.RABBIT, Items.COOKED_RABBIT,
		Items.COD, Items.COOKED_COD,
		Items.SALMON, Items.COOKED_SALMON
	);
	private static final int CHEST_RADIUS = 6;
	private static final int BATCH = 8;
	/** A batch smokes for this long (5 seconds), about a smoker's speed for a few items. */
	private static final int SMOKE_TICKS = 100;
	private static final int HUNGRY_AT = 3;
	private static final int HAND_OUT = 4;
	private static final double SEARCH_RADIUS = 24.0;

	private static final Map<Villager, Long> SMOKING_UNTIL = new WeakHashMap<>();
	/** The chest by each butcher's smoker, looked up now and then rather than every tick. */
	private static final Map<Villager, BlockPos> CHESTS = new WeakHashMap<>();

	private ButcherWork() {
	}

	public static boolean isButcher(Villager villager) {
		return WorkHelper.is(villager, VillagerProfession.BUTCHER);
	}

	private static boolean isRaw(ItemStack stack) {
		return COOKED.containsKey(stack.getItem());
	}

	private static boolean isCooked(ItemStack stack) {
		return COOKED.containsValue(stack.getItem());
	}

	public static void tick(Villager villager, ServerLevel level, BlockPos smoker) {
		Long smokingUntil = SMOKING_UNTIL.get(villager);
		if (smokingUntil != null) {
			smoke(villager, level, smoker, smokingUntil);
			return;
		}

		// 1. Cooked food: feed the hungry, otherwise store it.
		if (WorkHelper.pocketsHave(villager, ButcherWork::isCooked)) {
			Villager hungry = findHungry(villager, level, smoker);
			if (hungry != null) {
				if (WorkHelper.walkTo(villager, hungry, WorkHelper.REACH)) {
					feed(villager, hungry, level);
				}
				return;
			}
			BlockPos chest = chestFor(villager, level, smoker);
			if (chest != null && WorkHelper.walkTo(villager, chest, WorkHelper.REACH)) {
				Container container = WorkHelper.container(level, chest);
				if (container != null) {
					WorkHelper.unload(villager, container, ButcherWork::isCooked);
				}
			}
			return;
		}

		// 2. Raw meat: smoke it.
		if (WorkHelper.pocketsHave(villager, ButcherWork::isRaw)) {
			if (WorkHelper.walkTo(villager, smoker, WorkHelper.REACH)) {
				SMOKING_UNTIL.put(villager, level.getGameTime() + SMOKE_TICKS);
				setLit(level, smoker, true);
				WorkHelper.workSound(villager, level);
			}
			return;
		}

		// 3. Fetch raw meat from the chest.
		BlockPos chest = chestFor(villager, level, smoker);
		Container container = WorkHelper.container(level, chest);
		if (chest != null && container != null && containsRaw(container) && WorkHelper.walkTo(villager, chest, WorkHelper.REACH)) {
			WorkHelper.take(villager, container, ButcherWork::isRaw, BATCH);
			level.playSound(null, chest, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
		}
	}

	private static @Nullable BlockPos chestFor(Villager villager, ServerLevel level, BlockPos smoker) {
		if (!CHESTS.containsKey(villager) || (level.getGameTime() + villager.getId()) % 100 == 0) {
			CHESTS.put(villager, WorkHelper.closestChest(level, smoker, CHEST_RADIUS));
		}
		return CHESTS.get(villager);
	}

	private static void smoke(Villager villager, ServerLevel level, BlockPos smoker, long until) {
		WorkHelper.walkTo(villager, smoker, WorkHelper.REACH);
		if (villager.getRandom().nextInt(10) == 0) {
			level.sendParticles(ParticleTypes.SMOKE, smoker.getX() + 0.5, smoker.getY() + 1.1, smoker.getZ() + 0.5, 2, 0.15, 0.0, 0.15, 0.0);
		}
		if (level.getGameTime() < until) {
			return;
		}
		SMOKING_UNTIL.remove(villager);
		setLit(level, smoker, false);
		level.playSound(null, smoker, SoundEvents.SMOKER_SMOKE, SoundSource.BLOCKS, 1.0F, 1.0F);
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			ItemStack stack = pockets.getItem(i);
			Item cooked = COOKED.get(stack.getItem());
			if (cooked != null) {
				pockets.setItem(i, new ItemStack(cooked, stack.getCount()));
			}
		}
		pockets.setChanged();
	}

	private static void setLit(ServerLevel level, BlockPos smoker, boolean lit) {
		BlockState state = level.getBlockState(smoker);
		if (state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT) != lit) {
			level.setBlockAndUpdate(smoker, state.setValue(AbstractFurnaceBlock.LIT, lit));
		}
	}

	private static boolean containsRaw(Container container) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (isRaw(container.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The nearest villager of the same town whose food slot is running low and can take what the
	 * butcher carries (an empty slot, or the same food).
	 */
	private static @Nullable Villager findHungry(Villager butcher, ServerLevel level, BlockPos smoker) {
		BlockPos town = butcher.getAttached(ModRegistry.TOWN);
		return level.getEntitiesOfClass(Villager.class, new AABB(smoker).inflate(SEARCH_RADIUS), v -> v != butcher && v.isAlive()
				&& (town == null || town.equals(v.getAttached(ModRegistry.TOWN)))
				&& VillagerFood.get(v).getCount() <= HUNGRY_AT
				&& canTake(butcher, VillagerFood.get(v)))
			.stream().min(Comparator.comparingDouble(butcher::distanceToSqr)).orElse(null);
	}

	private static boolean canTake(Villager butcher, ItemStack slot) {
		return slot.isEmpty() || WorkHelper.pocketsHave(butcher, stack -> isCooked(stack) && ItemStack.isSameItemSameComponents(stack, slot));
	}

	/** Puts some cooked food in the villager's food slot (if it holds the same food or nothing). */
	private static void feed(Villager butcher, Villager hungry, ServerLevel level) {
		ItemStack slot = VillagerFood.get(hungry);
		SimpleContainer pockets = butcher.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			ItemStack stack = pockets.getItem(i);
			if (isCooked(stack) && (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, stack))) {
				int moved = Math.min(HAND_OUT, stack.getCount());
				VillagerFood.set(hungry, slot.isEmpty() ? stack.copyWithCount(moved) : slot.copyWithCount(slot.getCount() + moved));
				stack.shrink(moved);
				pockets.setChanged();
				level.playSound(null, hungry.getX(), hungry.getY(), hungry.getZ(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
				return;
			}
		}
	}
}
