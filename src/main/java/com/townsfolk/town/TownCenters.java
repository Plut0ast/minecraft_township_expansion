package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.townsfolk.ModRegistry;
import com.townsfolk.Townsfolk;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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
