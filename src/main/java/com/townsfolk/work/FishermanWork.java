package com.townsfolk.work;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fishermen fish at water near their barrel (vanilla fishing loot, at a vanilla-ish pace) and put the
 * catch in their barrel.
 */
public final class FishermanWork {
	private static final int SEARCH_RADIUS = 10;
	/** How long a cast takes before something bites, in ticks (5 to 15 seconds). */
	private static final int MIN_WAIT = 100;
	private static final int MAX_WAIT = 300;

	/** Where it is fishing: the water block, where it stands, and when the fish bites. */
	private record Cast(BlockPos water, BlockPos stand, long biteAt) {
	}

	private static final Map<Villager, Cast> CASTS = new WeakHashMap<>();

	private FishermanWork() {
	}

	public static void tick(Villager villager, ServerLevel level, BlockPos barrel) {
		// Anything caught goes straight to the barrel.
		if (WorkHelper.pocketsHave(villager, stack -> !stack.isEmpty())) {
			CASTS.remove(villager);
			if (WorkHelper.walkTo(villager, barrel, WorkHelper.REACH)) {
				Container container = WorkHelper.container(level, barrel);
				if (container != null) {
					WorkHelper.unload(villager, container, stack -> true);
					level.playSound(null, barrel, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
				}
			}
			return;
		}

		Cast cast = CASTS.get(villager);
		if (cast == null) {
			if ((level.getGameTime() + villager.getId()) % 40 != 0) {
				return;
			}
			cast = findSpot(level, barrel, villager);
			if (cast == null) {
				return;
			}
			CASTS.put(villager, cast);
		}

		if (!WorkHelper.walkTo(villager, cast.stand(), 1.2)) {
			return;
		}
		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(cast.water()));
		long now = level.getGameTime();
		if (cast.biteAt() == 0) {
			// Just arrived: cast the line.
			int wait = MIN_WAIT + villager.getRandom().nextInt(MAX_WAIT - MIN_WAIT);
			CASTS.put(villager, new Cast(cast.water(), cast.stand(), now + wait));
			level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 0.6F);
			return;
		}
		Vec3 bobber = Vec3.atCenterOf(cast.water()).add(0, 0.45, 0);
		if (now < cast.biteAt()) {
			if (villager.getRandom().nextInt(40) == 0) {
				level.sendParticles(ParticleTypes.FISHING, bobber.x, bobber.y, bobber.z, 2, 0.1, 0.0, 0.1, 0.0);
			}
			return;
		}

		// A bite: reel it in.
		level.sendParticles(ParticleTypes.SPLASH, bobber.x, bobber.y, bobber.z, 12, 0.2, 0.0, 0.2, 0.0);
		level.playSound(null, bobber.x, bobber.y, bobber.z, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.25F, 1.0F);
		level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0F, 1.0F);
		WorkHelper.workSound(villager, level);
		LootParams params = new LootParams.Builder(level)
			.withParameter(LootContextParams.ORIGIN, bobber)
			.withParameter(LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD))
			.withParameter(LootContextParams.THIS_ENTITY, villager)
			.create(LootContextParamSets.FISHING);
		for (ItemStack catchItem : level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING).getRandomItems(params)) {
			WorkHelper.pocket(villager, catchItem);
		}
		CASTS.remove(villager);
	}

	/** Open water near the barrel with somewhere dry to stand next to it. */
	private static @Nullable Cast findSpot(ServerLevel level, BlockPos barrel, Villager villager) {
		BlockPos best = null;
		BlockPos bestStand = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(barrel.offset(-SEARCH_RADIUS, -3, -SEARCH_RADIUS), barrel.offset(SEARCH_RADIUS, 2, SEARCH_RADIUS))) {
			if (!level.getFluidState(pos).is(FluidTags.WATER) || !level.getBlockState(pos.above()).isAir()) {
				continue;
			}
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos stand = pos.relative(direction).above();
				if (canStand(level, stand)) {
					double dist = stand.distSqr(villager.blockPosition());
					if (dist < bestDist) {
						best = pos.immutable();
						bestStand = stand.immutable();
						bestDist = dist;
					}
				}
			}
		}
		return best == null ? null : new Cast(best, bestStand, 0);
	}

	private static boolean canStand(ServerLevel level, BlockPos pos) {
		BlockPos below = pos.below();
		return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
			&& level.getFluidState(below).isEmpty() && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	public static boolean isFisherman(Villager villager) {
		return WorkHelper.is(villager, VillagerProfession.FISHERMAN);
	}
}
