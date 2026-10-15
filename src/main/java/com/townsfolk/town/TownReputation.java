package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.townsfolk.Townsfolk;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Each player's reputation with each town, stored per dimension and keyed by the Town Center position.
 * Kept in level data rather than the block entity so trades count even when the Town Center's chunk is unloaded.
 */
public class TownReputation extends SavedData {
	/**
	 * Ranks, lowest first. Enemy is reputation at or below {@link #ENEMY_AT}; above that each rank
	 * starts at its threshold. Stranger covers everything between Enemy and Visitor.
	 */
	public enum Rank {
		ENEMY(ENEMY_AT), STRANGER(ENEMY_AT + 1), VISITOR(10), FRIEND(50), TRUSTED(150), HERO(400);

		public final int threshold;

		Rank(int threshold) {
			this.threshold = threshold;
		}

		public static Rank of(int reputation) {
			if (reputation <= ENEMY_AT) {
				return ENEMY;
			}
			Rank rank = STRANGER;
			for (Rank r : values()) {
				if (r.ordinal() > STRANGER.ordinal() && reputation >= r.threshold) {
					rank = r;
				}
			}
			return rank;
		}

		/** The next rank up, or {@code null} at the top. */
		public Rank next() {
			return this.ordinal() + 1 < values().length ? values()[this.ordinal() + 1] : null;
		}

		public Component displayName() {
			return Component.translatable("rank.townsfolk." + this.name().toLowerCase());
		}
	}

	/** At or below this reputation a player is an enemy of the town. */
	public static final int ENEMY_AT = -10;

	private static final Codec<Map<String, Map<UUID, Integer>>> MAP_CODEC =
		Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT));
	public static final Codec<TownReputation> CODEC = MAP_CODEC.xmap(TownReputation::new, data -> data.towns);
	public static final SavedDataType<TownReputation> TYPE = new SavedDataType<>(
		Townsfolk.id("reputation"), TownReputation::new, CODEC, null
	);

	private final Map<String, Map<UUID, Integer>> towns = new HashMap<>();

	public TownReputation() {
	}

	private TownReputation(Map<String, Map<UUID, Integer>> towns) {
		towns.forEach((town, players) -> this.towns.put(town, new HashMap<>(players)));
	}

	public static TownReputation get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static String key(BlockPos town) {
		return Long.toString(town.asLong());
	}

	public int get(BlockPos town, UUID player) {
		Map<UUID, Integer> players = this.towns.get(key(town));
		return players == null ? 0 : players.getOrDefault(player, 0);
	}

	public void add(BlockPos town, UUID player, int amount) {
		this.towns.computeIfAbsent(key(town), k -> new HashMap<>()).merge(player, amount, Integer::sum);
		this.setDirty();
	}

	public void set(BlockPos town, UUID player, int amount) {
		this.towns.computeIfAbsent(key(town), k -> new HashMap<>()).put(player, amount);
		this.setDirty();
	}

	/** Forget a town's reputation when its Town Center is broken. */
	public void clear(BlockPos town) {
		if (this.towns.remove(key(town)) != null) {
			this.setDirty();
		}
	}
}
