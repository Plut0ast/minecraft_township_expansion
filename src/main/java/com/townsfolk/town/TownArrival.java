package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * A villager that joins a town walks to its Town Center and celebrates there with soft white
 * sparkles (unlike the green sparkles of getting a job) and a happy "hm-hm, hm-hm!".
 */
public final class TownArrival {
	/** Give up on the walk after this long (3 minutes) if the Town Center cannot be reached. */
	private static final long ARRIVAL_TIME_LIMIT = 3600;
	private static final double ARRIVED_DISTANCE = 3.0;
	private static final float WALK_SPEED = 0.6F;
	/** Ticks between the two happy sounds. */
	private static final int SECOND_CHEER_DELAY = 8;

	/** Villagers waiting for their second cheer, with the game time it is due. */
	private static final Map<Villager, Long> PENDING_CHEERS = new WeakHashMap<>();

	private TownArrival() {
	}

	/** Called when a villager joins a town. */
	public static void start(Villager villager, ServerLevel level) {
		villager.setAttached(ModRegistry.ARRIVAL_DEADLINE, level.getGameTime() + ARRIVAL_TIME_LIMIT);
	}

	/** Runs right after the villager's brain each tick, steering it towards its Town Center. */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		Long deadline = villager.getAttached(ModRegistry.ARRIVAL_DEADLINE);
		if (deadline == null) {
			return;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town == null || level.getGameTime() > deadline || TownCenters.isDestroyed(level, town)) {
			villager.removeAttached(ModRegistry.ARRIVAL_DEADLINE);
			return;
		}
		// Orders and sleep come first; the walk resumes afterwards.
		if (villager.isSleeping() || VillagerCommands.commandOf(villager) != VillagerCommands.Command.WANDER) {
			return;
		}

		if (villager.position().distanceTo(Vec3.atBottomCenterOf(town)) <= ARRIVED_DISTANCE) {
			villager.removeAttached(ModRegistry.ARRIVAL_DEADLINE);
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			celebrate(villager, level);
			return;
		}
		villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new BlockPosTracker(town), WALK_SPEED, 2));
	}

	private static void celebrate(Villager villager, ServerLevel level) {
		level.sendParticles(ParticleTypes.END_ROD,
			villager.getX(), villager.getY() + villager.getBbHeight() * 0.8, villager.getZ(),
			12, 0.4, 0.4, 0.4, 0.02);
		cheer(villager, level, 1.0F);
		PENDING_CHEERS.put(villager, level.getGameTime() + SECOND_CHEER_DELAY);
	}

	private static void cheer(Villager villager, ServerLevel level, float pitch) {
		level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, pitch);
	}

	/** Plays the second half of each "happy happy" once its delay is up. */
	public static void tick(ServerLevel level) {
		if (PENDING_CHEERS.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<Villager, Long>> it = PENDING_CHEERS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Villager, Long> entry = it.next();
			Villager villager = entry.getKey();
			if (villager.level() != level) {
				continue;
			}
			if (level.getGameTime() >= entry.getValue()) {
				it.remove();
				if (villager.isAlive()) {
					cheer(villager, level, 1.15F);
				}
			}
		}
	}
}
