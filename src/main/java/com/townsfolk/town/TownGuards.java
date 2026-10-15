package com.townsfolk.town;

import com.townsfolk.TownsfolkConfig;
import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.AABB;

/** A town's iron golems attack players who are enemies of that town. */
public final class TownGuards {
	private static final int CHECK_INTERVAL_TICKS = 20;

	private TownGuards() {
	}

	public static void tick(ServerLevel level) {
		if (!TownsfolkConfig.golemsAttackEnemies || level.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
			return;
		}

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
}
