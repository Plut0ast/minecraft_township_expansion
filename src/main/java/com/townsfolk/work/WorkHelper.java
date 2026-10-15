package com.townsfolk.work;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.VillagerCommands;
import com.townsfolk.town.VillagerFood;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Shared bits for villagers' daily work: where their workstation is, walking, pockets and chests. */
public final class WorkHelper {
	public static final float WALK_SPEED = 0.5F;
	public static final double REACH = 2.2;

	private WorkHelper() {
	}

	public static boolean is(Villager villager, ResourceKey<VillagerProfession> profession) {
		return villager.getVillagerData().profession().is(profession);
	}

	/**
	 * Villagers work by day, when nothing more important is going on: no order (other than Wander),
	 * not asleep, not walking to a new town, not off to fetch food.
	 */
	public static boolean canWork(Villager villager, ServerLevel level) {
		return !villager.isBaby() && !villager.isSleeping() && level.isBrightOutside()
			&& VillagerCommands.commandOf(villager) == VillagerCommands.Command.WANDER
			&& villager.getAttached(ModRegistry.ARRIVAL_DEADLINE) == null
			&& villager.getAttached(ModRegistry.EXILE) == null
			&& !VillagerFood.isFetchingFood(villager);
	}

	/** The villager's workstation (job site), if it has one in this dimension. */
	public static @Nullable BlockPos jobSite(Villager villager, ServerLevel level) {
		GlobalPos site = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
		return site != null && site.dimension() == level.dimension() ? site.pos() : null;
	}

	/** Walks towards a block. Returns true once within {@code reach}. */
	public static boolean walkTo(Villager villager, BlockPos pos, double reach) {
		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
		if (villager.position().distanceTo(Vec3.atBottomCenterOf(pos)) <= reach) {
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			return true;
		}
		villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new BlockPosTracker(pos), WALK_SPEED, 1));
		return false;
	}

	/** Walks towards an entity. Returns true once within {@code reach}. */
	public static boolean walkTo(Villager villager, Entity target, double reach) {
		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
		if (villager.distanceTo(target) <= reach) {
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			return true;
		}
		villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
		return false;
	}

	/** The profession's own vanilla work sound, at the villager. */
	public static void workSound(Villager villager, ServerLevel level) {
		Holder<VillagerProfession> profession = villager.getVillagerData().profession();
		SoundEvent sound = profession.value().workSound();
		if (sound != null) {
			level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), sound, SoundSource.NEUTRAL, 1.0F, 1.0F);
		}
	}

	/** The closest chest or barrel within {@code radius} of {@code from}, searched in loaded chunks. */
	public static @Nullable BlockPos closestChest(ServerLevel level, BlockPos from, int radius) {
		BlockPos best = null;
		double bestDist = (double) radius * radius;
		for (int cx = (from.getX() - radius) >> 4; cx <= (from.getX() + radius) >> 4; cx++) {
			for (int cz = (from.getZ() - radius) >> 4; cz <= (from.getZ() + radius) >> 4; cz++) {
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

	public static @Nullable Container container(ServerLevel level, @Nullable BlockPos pos) {
		return pos == null ? null : HopperBlockEntity.getContainerAt(level, pos);
	}

	/** Puts as much of {@code stack} as fits into the villager's pockets; returns what did not fit. */
	public static ItemStack pocket(Villager villager, ItemStack stack) {
		return villager.getInventory().addItem(stack);
	}

	public static boolean pocketsHave(Villager villager, Predicate<ItemStack> filter) {
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			if (filter.test(pockets.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	public static boolean pocketsFull(Villager villager) {
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			if (pockets.getItem(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Moves every pocket item matching {@code filter} into the container (what does not fit stays). */
	public static void unload(Villager villager, Container into, Predicate<ItemStack> filter) {
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			ItemStack stack = pockets.getItem(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				ItemStack left = HopperBlockEntity.addItem(null, into, stack.copy(), null);
				pockets.setItem(i, left);
			}
		}
		pockets.setChanged();
		into.setChanged();
	}

	/** Takes up to {@code max} items matching {@code filter} out of the container into the pockets. */
	public static int take(Villager villager, Container from, Predicate<ItemStack> filter, int max) {
		int taken = 0;
		for (int i = 0; i < from.getContainerSize() && taken < max; i++) {
			ItemStack stack = from.getItem(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				ItemStack moving = stack.copyWithCount(Math.min(max - taken, stack.getCount()));
				int moved = moving.getCount() - pocket(villager, moving).getCount();
				stack.shrink(moved);
				taken += moved;
				if (moved == 0) {
					break; // pockets full
				}
			}
		}
		from.setChanged();
		return taken;
	}
}
