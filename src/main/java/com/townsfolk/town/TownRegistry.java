package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.Townsfolk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every town in a dimension, so a Town Center screen can browse towns whose chunks are not loaded.
 * Each Town Center keeps its own entry up to date (name, residents) whenever it is loaded; player
 * membership (founders and Heroes who joined) lives only here.
 */
public class TownRegistry extends SavedData {
	/**
	 * A town as last seen. {@code villagerJobs} lines up with {@code villagers} (translation keys);
	 * {@code players} maps member UUIDs to the names they had when they joined. {@code banner} is the
	 * banner in the Town Center's banner slot (empty if none).
	 */
	public record Town(String name, List<String> villagers, List<String> villagerJobs, List<String> golems, Map<UUID, String> players,
		ItemStack banner) {
		static final Codec<Town> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("name").forGetter(Town::name),
			Codec.STRING.listOf().fieldOf("villagers").forGetter(Town::villagers),
			Codec.STRING.listOf().optionalFieldOf("villager_jobs", List.of()).forGetter(Town::villagerJobs),
			Codec.STRING.listOf().fieldOf("golems").forGetter(Town::golems),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).fieldOf("players").forGetter(Town::players),
			ItemStack.OPTIONAL_CODEC.optionalFieldOf("banner", ItemStack.EMPTY).forGetter(Town::banner)
		).apply(i, Town::new));

		Town withPlayers(Map<UUID, String> players) {
			return new Town(this.name, this.villagers, this.villagerJobs, this.golems, players, this.banner);
		}

		/** Records compare item stacks by identity, so compare the banner by contents. */
		boolean sameAs(Town other) {
			return other != null && this.name.equals(other.name) && this.villagers.equals(other.villagers)
				&& this.villagerJobs.equals(other.villagerJobs) && this.golems.equals(other.golems) && this.players.equals(other.players)
				&& ItemStack.matches(this.banner, other.banner);
		}
	}

	public static final Codec<TownRegistry> CODEC = Codec.unboundedMap(Codec.STRING, Town.CODEC)
		.xmap(TownRegistry::new, registry -> registry.towns);
	public static final SavedDataType<TownRegistry> TYPE = new SavedDataType<>(
		Townsfolk.id("towns"), TownRegistry::new, CODEC, null
	);

	private final Map<String, Town> towns = new HashMap<>();

	public TownRegistry() {
	}

	private TownRegistry(Map<String, Town> towns) {
		towns.forEach((key, town) -> this.towns.put(key, new Town(
			town.name(), List.copyOf(town.villagers()), List.copyOf(town.villagerJobs()), List.copyOf(town.golems()),
			new LinkedHashMap<>(town.players()), town.banner())));
	}

	public static TownRegistry get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static String key(BlockPos pos) {
		return Long.toString(pos.asLong());
	}

	/** All towns with their Town Center positions. */
	public Map<BlockPos, Town> all() {
		Map<BlockPos, Town> result = new HashMap<>();
		this.towns.forEach((key, town) -> result.put(BlockPos.of(Long.parseLong(key)), town));
		return result;
	}

	/** Records a Town Center's current name, residents and banner; player membership is kept. */
	public void update(BlockPos pos, String name, List<String> villagers, List<String> villagerJobs, List<String> golems, ItemStack banner) {
		Town old = this.towns.get(key(pos));
		Town updated = new Town(name, List.copyOf(villagers), List.copyOf(villagerJobs), List.copyOf(golems),
			old == null ? new LinkedHashMap<>() : old.players(), banner.copy());
		if (!updated.sameAs(old)) {
			this.towns.put(key(pos), updated);
			this.setDirty();
		}
	}

	public boolean isMember(BlockPos pos, UUID player) {
		Town town = this.towns.get(key(pos));
		return town != null && town.players().containsKey(player);
	}

	public void addMember(BlockPos pos, UUID player, String playerName) {
		Town town = this.towns.get(key(pos));
		Map<UUID, String> players = new LinkedHashMap<>(town == null ? Map.of() : town.players());
		players.put(player, playerName);
		this.towns.put(key(pos), town == null
			? new Town("", List.of(), List.of(), List.of(), players, ItemStack.EMPTY)
			: town.withPlayers(players));
		this.setDirty();
	}

	public void remove(BlockPos pos) {
		if (this.towns.remove(key(pos)) != null) {
			this.setDirty();
		}
	}

	public List<String> playerNames(BlockPos pos) {
		Town town = this.towns.get(key(pos));
		return town == null ? List.of() : new ArrayList<>(town.players().values());
	}
}
