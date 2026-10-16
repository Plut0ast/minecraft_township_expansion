package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.TownsfolkConfig;
import com.townsfolk.block.TownCenterBlockEntity;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raid;

/**
 * Messages above the hotbar for a town's players (wherever they are) when something happens to their
 * town: one of its villagers dies, a raid starts there, or an enemy player walks in.
 */
public final class TownAlerts {
	private static final int CHECK_INTERVAL_TICKS = 20;

	/** Raids already announced, and to which towns. */
	private static final Map<Raid, Set<BlockPos>> ANNOUNCED_RAIDS = new WeakHashMap<>();
	/** "town|player" for enemy players already announced as being in that town's area. */
	private static final Set<String> ENEMIES_INSIDE = new HashSet<>();

	private TownAlerts() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (TownsfolkConfig.townAlerts && entity instanceof Villager villager && villager.level() instanceof ServerLevel level) {
				BlockPos town = villager.getAttached(ModRegistry.TOWN);
				if (town == null) {
					return;
				}
				Entity killer = source.getEntity();
				Component name = Component.literal(VillagerNames.nameOf(villager));
				send(level, town, killer == null
					? Component.translatable("message.townsfolk.alert.died", townName(level, town), name)
					: Component.translatable("message.townsfolk.alert.killed", townName(level, town), name, killer.getDisplayName()));
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(TownAlerts::tick);
	}

	private static void tick(ServerLevel level) {
		if (!TownsfolkConfig.townAlerts || level.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		checkRaids(level);
		checkEnemies(level);
	}

	private static void checkRaids(ServerLevel level) {
		for (TownCenterBlockEntity center : TownCenters.loaded(level)) {
			Raid raid = level.getRaidAt(center.getBlockPos());
			if (raid == null || raid.isOver()) {
				continue;
			}
			BlockPos town = center.townPos();
			if (ANNOUNCED_RAIDS.computeIfAbsent(raid, r -> new HashSet<>()).add(town)) {
				send(level, town, Component.translatable("message.townsfolk.alert.raid", townName(level, town)));
			}
		}
	}

	/** Players from towns this town treats as enemies, and players who are personally its Enemies. */
	private static void checkEnemies(ServerLevel level) {
		Set<String> inside = new HashSet<>();
		TownReputation reputation = TownReputation.get(level);
		for (ServerPlayer player : level.players()) {
			if (player.isCreative() || player.isSpectator()) {
				continue;
			}
			Set<BlockPos> towns = new HashSet<>();
			for (TownCenterBlockEntity center : TownCenters.townsNear(level, player.blockPosition(), null)) {
				towns.add(center.townPos());
			}
			for (BlockPos town : towns) {
				boolean enemyTown = TownRelations.towards(level, town, player) == TownRelations.Relation.ENEMY;
				boolean enemyPlayer = TownReputation.Rank.of(reputation.get(town, player.getUUID())) == TownReputation.Rank.ENEMY;
				if (!enemyTown && !enemyPlayer) {
					continue;
				}
				String key = town.asLong() + "|" + player.getUUID();
				inside.add(key);
				if (!ENEMIES_INSIDE.contains(key)) {
					BlockPos theirs = TownRelations.townOf(level, player);
					send(level, town, enemyTown && theirs != null
						? Component.translatable("message.townsfolk.alert.enemy_town", townName(level, town), player.getDisplayName(),
							townName(level, theirs))
						: Component.translatable("message.townsfolk.alert.enemy_player", townName(level, town), player.getDisplayName()));
				}
			}
		}
		// Forget players who have left (in this dimension); other dimensions keep theirs.
		String dimension = level.dimension().identifier().toString();
		ENEMIES_INSIDE.removeIf(key -> !inside.contains(key) && DIMENSION_OF.getOrDefault(key, dimension).equals(dimension));
		for (String key : inside) {
			DIMENSION_OF.put(key, dimension);
		}
		ENEMIES_INSIDE.addAll(inside);
	}

	private static final Map<String, String> DIMENSION_OF = new java.util.HashMap<>();

	private static Component townName(ServerLevel level, BlockPos town) {
		TownRegistry.Town data = TownRegistry.get(level).town(town);
		return Component.literal(data == null ? "?" : data.name());
	}

	/** Sends the message above the hotbar of every online player of the town. */
	private static void send(ServerLevel level, BlockPos town, Component message) {
		TownRegistry.Town data = TownRegistry.get(level).town(town);
		if (data == null) {
			return;
		}
		for (UUID id : data.players().keySet()) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
			if (player != null) {
				player.sendSystemMessage(message, true);
			}
		}
	}
}
