package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.TownsfolkConfig;
import com.townsfolk.block.TownCenterBlockEntity;
import com.townsfolk.mixin.RaidAccessor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;

/**
 * Reputation for looking after a town, besides trading: killing hostile mobs in its area (a few a
 * day), winning a raid on it, and curing a zombie villager that then joins it.
 */
public final class TownRewards {
	private static final int CHECK_INTERVAL_TICKS = 20;

	/** "town|player" to mob kills already rewarded today, and which day that is. */
	private static final Map<String, Integer> KILLS_TODAY = new HashMap<>();
	private static long killDay = -1;
	/** Per dimension, the towns each raid has been seen in (rewarded and forgotten once it is won). */
	private static final Map<ResourceKey<Level>, Map<Raid, Set<BlockPos>>> RAIDS = new HashMap<>();

	private TownRewards() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer player && entity.level() instanceof ServerLevel level) {
				onMobKilled(level, player, entity.blockPosition());
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(TownRewards::tickRaids);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			KILLS_TODAY.clear();
			killDay = -1;
			RAIDS.clear();
		});
	}

	/** Every town whose area the mob died in rewards the player, up to the daily limit per town. */
	private static void onMobKilled(ServerLevel level, ServerPlayer player, BlockPos where) {
		if (TownsfolkConfig.reputationPerMobKill == 0) {
			return;
		}
		long day = level.getOverworldClockTime() / 24000L;
		if (day != killDay) {
			KILLS_TODAY.clear();
			killDay = day;
		}
		Set<BlockPos> towns = new HashSet<>();
		for (TownCenterBlockEntity center : TownCenters.townsNear(level, where, null)) {
			towns.add(center.townPos());
		}
		TownReputation reputation = TownReputation.get(level);
		for (BlockPos town : towns) {
			String key = town.asLong() + "|" + player.getUUID();
			int kills = KILLS_TODAY.getOrDefault(key, 0);
			if (kills < TownsfolkConfig.mobKillDailyLimit) {
				KILLS_TODAY.put(key, kills + 1);
				reputation.add(town, player.getUUID(), TownsfolkConfig.reputationPerMobKill);
			}
		}
	}

	/** Notes which towns each raid is in, and rewards its Heroes of the Village once it is won. */
	private static void tickRaids(ServerLevel level) {
		if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		Map<Raid, Set<BlockPos>> raids = RAIDS.computeIfAbsent(level.dimension(), k -> new WeakHashMap<>());
		for (TownCenterBlockEntity center : TownCenters.loaded(level)) {
			Raid raid = level.getRaidAt(center.getBlockPos());
			if (raid != null && !raid.isVictory()) {
				raids.computeIfAbsent(raid, r -> new HashSet<>()).add(center.townPos());
			}
		}
		raids.entrySet().removeIf(entry -> {
			Raid raid = entry.getKey();
			if (raid.isLoss() || raid.isStopped()) {
				return true;
			}
			if (!raid.isVictory()) {
				return false;
			}
			TownReputation reputation = TownReputation.get(level);
			for (UUID hero : ((RaidAccessor) raid).townsfolk$heroes()) {
				for (BlockPos town : entry.getValue()) {
					reputation.add(town, hero, TownsfolkConfig.reputationPerRaidWin);
				}
			}
			return true;
		});
	}

	/** Called when a villager joins a town: whoever cured it (from a zombie villager) is rewarded. */
	public static void onJoinedTown(Villager villager, ServerLevel level, BlockPos town) {
		UUID curer = villager.getAttached(ModRegistry.CURED_BY);
		if (curer == null) {
			return;
		}
		villager.removeAttached(ModRegistry.CURED_BY);
		TownReputation.get(level).add(town, curer, TownsfolkConfig.reputationPerCure);
	}
}
