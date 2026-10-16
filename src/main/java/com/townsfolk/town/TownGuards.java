package com.townsfolk.town;

import com.townsfolk.TownsfolkConfig;
import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityReference;
import java.util.Comparator;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.AABB;

/**
 * A town's iron golems attack players who are its Enemies and every member of towns it treats as an
 * enemy, leave members of towns it is on good terms with (and of their own town) alone, and otherwise
 * fight back as usual when hit.
 */
public final class TownGuards {
	private static final int CHECK_INTERVAL_TICKS = 20;
	/** How far a golem looks for members of enemy towns. */
	private static final double ENEMY_SEARCH_RADIUS = 16.0;

	private TownGuards() {
	}

	public static void tick(ServerLevel level) {
		if (!TownsfolkConfig.golemsAttackEnemies || level.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
			return;
		}

		guardAgainstTowns(level);

		TownReputation reputation = TownReputation.get(level);
		for (ServerPlayer player : level.players()) {
			if (player.isCreative() || player.isSpectator()) {
				continue;
			}
			for (TownCenterBlockEntity town : TownCenters.townsNear(level, player.blockPosition(), null)) {
				BlockPos townPos = town.getBlockPos();
				if (TownReputation.Rank.of(reputation.get(townPos, player.getUUID())) != TownReputation.Rank.ENEMY) {
					continue;
				}
				AABB area = new AABB(townPos).inflate(TownCenterBlockEntity.CLAIM_RADIUS);
				for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, area,
					g -> g.isAlive() && townPos.equals(g.getAttached(ModRegistry.TOWN)) && g.getTarget() != player)) {
					golem.setPersistentAngerTarget(EntityReference.of(player));
					golem.startPersistentAngerTimer();
					golem.setTarget(player);
				}
			}
		}
	}

	private static void guardAgainstTowns(ServerLevel level) {
		for (IronGolem golem : level.getEntities(EntityTypes.IRON_GOLEM, g -> g.isAlive() && g.getAttached(ModRegistry.TOWN) != null)) {
			BlockPos town = golem.getAttached(ModRegistry.TOWN);
			LivingEntity target = golem.getTarget();
			if (target != null && spares(level, town, target)) {
				golem.setTarget(null);
				golem.stopBeingAngry();
				continue;
			}
			if (target != null && target.isAlive()) {
				continue;
			}
			AABB area = golem.getBoundingBox().inflate(ENEMY_SEARCH_RADIUS);
			level.getEntitiesOfClass(LivingEntity.class, area, e -> e != golem && e.isAlive() && isTownEnemy(level, town, e)).stream()
				.min(Comparator.comparingDouble(golem::distanceToSqr))
				.ifPresent(golem::setTarget);
		}
	}

	private static boolean isTownEnemy(ServerLevel level, BlockPos town, LivingEntity entity) {
		if (entity instanceof ServerPlayer player && (player.isCreative() || player.isSpectator())) {
			return false;
		}
		return TownRelations.towards(level, town, entity) == TownRelations.Relation.ENEMY;
	}

	/** Golems never fight their own town, nor towns they are on good terms with (unless a player there is an Enemy). */
	private static boolean spares(ServerLevel level, BlockPos town, LivingEntity target) {
		if (TownRelations.isMemberOf(level, town, target)) {
			return true;
		}
		if (TownRelations.towards(level, town, target) != TownRelations.Relation.GOOD) {
			return false;
		}
		return !(target instanceof ServerPlayer player
			&& TownReputation.Rank.of(TownReputation.get(level).get(town, player.getUUID())) == TownReputation.Rank.ENEMY);
	}
}
