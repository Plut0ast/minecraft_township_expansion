package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.townsfolk.ModRegistry;
import com.townsfolk.Townsfolk;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * How each town treats every other town, set by its Trusted players at its Town Center. One-way: a
 * town's stance only decides what its own villagers, golems and Sentries do. It covers every member
 * of the other town: villagers, Sentries, iron golems and players.
 */
public class TownRelations extends SavedData {
	public enum Relation {
		/** Villagers hide from them; iron golems and Sentries attack them. */
		ENEMY,
		/** Left alone unless they hit a golem or Sentry, which then fights back. */
		NEUTRAL,
		/** Never attacked (unless a player is personally an Enemy of the town). */
		GOOD;

		public static final Codec<Relation> CODEC = Codec.STRING.xmap(Relation::valueOf, Relation::name);

		public Relation next() {
			return values()[(this.ordinal() + 1) % values().length];
		}

		public static Relation byId(int id) {
			return id >= 0 && id < values().length ? values()[id] : NEUTRAL;
		}
	}

	/** Town Center (as a long) to the towns it has a stance on; Neutral is not stored. */
	public static final Codec<TownRelations> CODEC = Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Relation.CODEC))
		.xmap(TownRelations::new, relations -> relations.stances);
	public static final SavedDataType<TownRelations> TYPE = new SavedDataType<>(Townsfolk.id("town_relations"), TownRelations::new, CODEC, null);

	private final Map<String, Map<String, Relation>> stances = new HashMap<>();

	public TownRelations() {
	}

	private TownRelations(Map<String, Map<String, Relation>> stances) {
		stances.forEach((from, to) -> this.stances.put(from, new HashMap<>(to)));
	}

	public static TownRelations get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static String key(BlockPos pos) {
		return Long.toString(pos.asLong());
	}

	/** How the town at {@code from} treats the town at {@code to}. */
	public Relation get(BlockPos from, BlockPos to) {
		Map<String, Relation> towns = this.stances.get(key(from));
		return towns == null ? Relation.NEUTRAL : towns.getOrDefault(key(to), Relation.NEUTRAL);
	}

	public void set(BlockPos from, BlockPos to, Relation relation) {
		if (from.equals(to)) {
			return;
		}
		Map<String, Relation> towns = this.stances.computeIfAbsent(key(from), k -> new HashMap<>());
		if (relation == Relation.NEUTRAL) {
			towns.remove(key(to));
		} else {
			towns.put(key(to), relation);
		}
		this.setDirty();
	}

	/** A broken Town Center's stances, and every stance on it, are forgotten. */
	public void clear(BlockPos pos) {
		boolean changed = this.stances.remove(key(pos)) != null;
		for (Map<String, Relation> towns : this.stances.values()) {
			changed |= towns.remove(key(pos)) != null;
		}
		if (changed) {
			this.setDirty();
		}
	}

	/** The town an entity belongs to: its town for villagers and golems, the town a player has joined. */
	public static @Nullable BlockPos townOf(ServerLevel level, Entity entity) {
		if (entity instanceof ServerPlayer player) {
			return TownRegistry.get(level).townOf(player.getUUID());
		}
		return entity.getAttached(ModRegistry.TOWN);
	}

	/**
	 * How {@code town} treats {@code entity}: by the entity's own town; null when it has none or it is
	 * a member of {@code town} itself.
	 */
	public static @Nullable Relation towards(ServerLevel level, BlockPos town, Entity entity) {
		BlockPos theirs = townOf(level, entity);
		if (theirs == null || theirs.equals(town)) {
			return null;
		}
		return get(level).get(town, theirs);
	}

	public static boolean isMemberOf(ServerLevel level, BlockPos town, Entity entity) {
		return town.equals(townOf(level, entity));
	}
}
