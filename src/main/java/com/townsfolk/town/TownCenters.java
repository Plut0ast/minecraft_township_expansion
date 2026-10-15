package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.townsfolk.ModRegistry;
import com.townsfolk.Townsfolk;
import com.townsfolk.block.TownCenterBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Gives every village a Town Center. When a player is inside a village that has none yet, one is placed
 * next to the village bell (or in the middle of the village's central piece if there is no bell).
 * Works for new villages and villages in existing worlds alike.
 */
public final class TownCenters {
	private static final int CHECK_INTERVAL_TICKS = 40;
	private static final Map<ResourceKey<Level>, Set<BlockPos>> LOADED = new ConcurrentHashMap<>();

	private TownCenters() {
	}

	/** Villages (by structure start chunk) that already have their Town Center. */
	public static class VillageData extends SavedData {
		public static final Codec<VillageData> CODEC = Codec.LONG.listOf()
			.xmap(VillageData::new, data -> List.copyOf(data.handled));
		public static final SavedDataType<VillageData> TYPE = new SavedDataType<>(
			Townsfolk.id("villages"), VillageData::new, CODEC, null
		);

		private final Set<Long> handled = new HashSet<>();

		public VillageData() {
		}

		private VillageData(List<Long> handled) {
			this.handled.addAll(handled);
		}

		boolean isHandled(long village) {
			return this.handled.contains(village);
		}

		void markHandled(long village) {
			if (this.handled.add(village)) {
				this.setDirty();
			}
		}
	}

	public static void track(ServerLevel level, BlockPos pos) {
		LOADED.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
	}

	public static void untrack(ServerLevel level, BlockPos pos) {
		Set<BlockPos> set = LOADED.get(level.dimension());
		if (set != null) {
			set.remove(pos);
		}
	}

	/** True when the chunk at {@code pos} is loaded and no Town Center stands there any more. */
	public static boolean isDestroyed(ServerLevel level, BlockPos pos) {
		return level.isLoaded(pos) && !(level.getBlockEntity(pos) instanceof TownCenterBlockEntity);
	}

	/** The town a villager without a town at {@code pos} should join: the newest one in range, nearest on ties. */
	public static @Nullable BlockPos townFor(ServerLevel level, BlockPos pos) {
		TownCenterBlockEntity best = null;
		for (TownCenterBlockEntity town : townsNear(level, pos, null)) {
			if (best == null
				|| town.foundedAt() > best.foundedAt()
				|| town.foundedAt() == best.foundedAt() && town.getBlockPos().distSqr(pos) < best.getBlockPos().distSqr(pos)) {
				best = town;
			}
		}
		return best == null ? null : best.getBlockPos();
	}

	/** The closest other town whose area overlaps {@code pos}, if any. */
	public static @Nullable TownCenterBlockEntity nearestOtherTown(ServerLevel level, BlockPos pos) {
		return townsNear(level, pos, pos).stream()
			.min(Comparator.comparingDouble(town -> town.getBlockPos().distSqr(pos)))
			.orElse(null);
	}

	/** Villagers and iron golems can belong to a town. */
	public static boolean canJoinTown(Mob mob) {
		return mob instanceof Villager || mob instanceof IronGolem;
	}

	/** Town Centers whose area contains {@code pos}, optionally skipping the one at {@code exclude}. */
	public static List<TownCenterBlockEntity> townsNear(ServerLevel level, BlockPos pos, @Nullable BlockPos exclude) {
		Set<BlockPos> loaded = LOADED.get(level.dimension());
		List<TownCenterBlockEntity> towns = new ArrayList<>();
		if (loaded == null) {
			return towns;
		}
		int r = TownCenterBlockEntity.CLAIM_RADIUS;
		for (BlockPos townPos : loaded) {
			if (townPos.equals(exclude)
				|| Math.abs(townPos.getX() - pos.getX()) > r
				|| Math.abs(townPos.getY() - pos.getY()) > r
				|| Math.abs(townPos.getZ() - pos.getZ()) > r) {
				continue;
			}
			if (level.getBlockEntity(townPos) instanceof TownCenterBlockEntity town && !town.isRemoved()) {
				towns.add(town);
			}
		}
		return towns;
	}

	public static void tick(ServerLevel level) {
		if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0 || level.players().isEmpty()) {
			return;
		}

		VillageData data = level.getDataStorage().computeIfAbsent(VillageData.TYPE);
		for (ServerPlayer player : level.players()) {
			StructureStart village = level.structureManager().getStructureWithPieceAt(player.blockPosition(), StructureTags.VILLAGE);
			if (!village.isValid() || village.getPieces().isEmpty()) {
				continue;
			}

			long key = village.getChunkPos().pack();
			if (data.isHandled(key)) {
				continue;
			}

			if (hasTownCenterIn(level, village.getBoundingBox()) || tryPlace(level, village)) {
				data.markHandled(key);
			}
		}
	}

	private static boolean hasTownCenterIn(ServerLevel level, BoundingBox box) {
		Set<BlockPos> loaded = LOADED.get(level.dimension());
		if (loaded == null) {
			return false;
		}
		BoundingBox area = box.inflatedBy(8);
		return loaded.stream().anyMatch(area::isInside);
	}

	private static boolean tryPlace(ServerLevel level, StructureStart village) {
		StructurePiece center = village.getPieces().getFirst();
		BoundingBox box = center.getBoundingBox();
		if (!level.hasChunksAt(box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
			return false; // try again once the centre of the village is loaded
		}

		BlockPos spot = findSpotNearBell(level, box);
		if (spot == null) {
			BlockPos middle = box.getCenter();
			spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, middle);
			if (!canPlaceAt(level, spot)) {
				spot = null;
			}
		}
		if (spot == null) {
			Townsfolk.LOGGER.debug("No room for a Town Center in village at {}", village.getChunkPos());
			return false;
		}

		level.setBlockAndUpdate(spot, ModRegistry.TOWN_CENTER.defaultBlockState());
		Townsfolk.LOGGER.info("Placed Town Center at {}", spot);
		return true;
	}

	private static BlockPos findSpotNearBell(ServerLevel level, BoundingBox box) {
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (!level.getBlockState(pos).is(Blocks.BELL)) {
				continue;
			}
			BlockPos bell = pos.immutable();
			for (int distance = 2; distance <= 4; distance++) {
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					for (int dy = 0; dy >= -3; dy--) {
						BlockPos candidate = bell.relative(direction, distance).above(dy);
						if (canPlaceAt(level, candidate)) {
							return candidate;
						}
					}
				}
			}
			return null;
		}
		return null;
	}

	private static boolean canPlaceAt(ServerLevel level, BlockPos pos) {
		BlockPos below = pos.below();
		return level.getBlockState(pos).canBeReplaced()
			&& level.getFluidState(pos).isEmpty()
			&& level.getBlockState(pos.above()).canBeReplaced()
			&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}
}
