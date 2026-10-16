package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A town's villagers (not its Sentries) run and hide from members of towns it treats as enemies. */
public final class TownHiding {
	private static final double SPOT_RADIUS = 12.0;
	private static final int CHECK_INTERVAL_TICKS = 10;
	private static final float FLEE_SPEED = 0.6F;

	/** The enemy each villager is currently running from. */
	private static final Map<Villager, LivingEntity> FLEEING = new WeakHashMap<>();

	private TownHiding() {
	}

	/** Runs after the villager's brain each tick. Returns true while it is running from an enemy. */
	public static boolean afterBrainTick(Villager villager, ServerLevel level) {
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town == null || villager.isSleeping() || Sentry.isSentry(villager)) {
			FLEEING.remove(villager);
			return false;
		}
		LivingEntity enemy = FLEEING.get(villager);
		if ((level.getGameTime() + villager.getId()) % CHECK_INTERVAL_TICKS == 0) {
			enemy = nearestEnemy(level, villager, town);
			if (enemy == null) {
				FLEEING.remove(villager);
			} else {
				FLEEING.put(villager, enemy);
			}
		}
		if (enemy == null || !enemy.isAlive()) {
			return false;
		}
		if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET) || villager.getNavigation().isDone()) {
			Vec3 away = DefaultRandomPos.getPosAway(villager, 16, 7, enemy.position());
			if (away != null) {
				villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(away, FLEE_SPEED, 0));
			}
		}
		return true;
	}

	private static @Nullable LivingEntity nearestEnemy(ServerLevel level, Villager villager, BlockPos town) {
		AABB area = villager.getBoundingBox().inflate(SPOT_RADIUS);
		return level.getEntitiesOfClass(LivingEntity.class, area, e -> e != villager && e.isAlive() && isEnemy(level, town, e)).stream()
			.min(Comparator.comparingDouble(villager::distanceToSqr))
			.orElse(null);
	}

	private static boolean isEnemy(ServerLevel level, BlockPos town, LivingEntity entity) {
		if (entity instanceof ServerPlayer player && (player.isCreative() || player.isSpectator())) {
			return false;
		}
		return TownRelations.towards(level, town, entity) == TownRelations.Relation.ENEMY;
	}
}
