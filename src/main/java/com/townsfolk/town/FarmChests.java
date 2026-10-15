package com.townsfolk.town;

import com.townsfolk.TownsfolkConfig;
import com.mojang.serialization.Codec;
import com.townsfolk.Townsfolk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Puts a chest beside every farm in a village, holding a few seeds for the crops that farm grows,
 * so farmers ordered to farm have somewhere to store the harvest and seeds to replant.
 * Farms are found by structure piece name, which works for every village type.
 */
public final class FarmChests {
	private static final int SEEDS_PER_CROP = 8;

	/** Villages (by structure start chunk) whose farms already have chests. */
	public static class Data extends SavedData {
		public static final Codec<Data> CODEC = Codec.LONG.listOf().xmap(Data::new, data -> List.copyOf(data.done));
		public static final SavedDataType<Data> TYPE = new SavedDataType<>(Townsfolk.id("farm_chests"), Data::new, CODEC, null);

		private final Set<Long> done = new HashSet<>();

		public Data() {
		}

		private Data(List<Long> done) {
			this.done.addAll(done);
		}
	}

	private FarmChests() {
	}

	/** Places the village's farm chests once all its farms are loaded. Safe to call repeatedly. */
	public static void ensurePlaced(ServerLevel level, StructureStart village) {
		if (!TownsfolkConfig.farmChests) {
			return;
		}
		Data data = level.getDataStorage().computeIfAbsent(Data.TYPE);
		long key = village.getChunkPos().pack();
		if (data.done.contains(key)) {
			return;
		}

		List<BoundingBox> farms = new ArrayList<>();
		for (StructurePiece piece : village.getPieces()) {
			if (piece instanceof PoolElementStructurePiece pooled && pooled.getElement().toString().contains("farm")) {
				BoundingBox box = piece.getBoundingBox();
				if (!level.hasChunksAt(box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
					return; // try again once every farm is loaded
				}
				farms.add(box);
			}
		}
		for (BoundingBox farm : farms) {
			placeChest(level, farm);
		}
		data.done.add(key);
		data.setDirty();
	}

	private static void placeChest(ServerLevel level, BoundingBox farm) {
		List<BlockPos> farmland = new ArrayList<>();
		Set<Item> seeds = new LinkedHashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(farm.minX(), farm.minY(), farm.minZ(), farm.maxX(), farm.maxY(), farm.maxZ())) {
			if (level.getBlockState(pos).getBlock() instanceof FarmlandBlock) {
				farmland.add(pos.immutable());
				Item seed = seedFor(level.getBlockState(pos.above()).getBlock());
				if (seed != null) {
					seeds.add(seed);
				}
			}
		}
		if (farmland.isEmpty()) {
			return;
		}
		if (seeds.isEmpty()) {
			seeds.add(Items.WHEAT_SEEDS);
		}

		// Stand the chest on a border block (a log or path) next to the farmland, facing away from the crops.
		for (BlockPos soil : farmland) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos border = soil.relative(direction);
				BlockState borderState = level.getBlockState(border);
				BlockPos spot = border.above();
				if (borderState.getBlock() instanceof FarmlandBlock
					|| !borderState.getFluidState().isEmpty()
					|| !borderState.isFaceSturdy(level, border, Direction.UP)
					|| !level.getBlockState(spot).canBeReplaced()
					|| !level.getBlockState(spot.above()).canBeReplaced()) {
					continue;
				}
				level.setBlockAndUpdate(spot, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, direction));
				if (level.getBlockEntity(spot) instanceof ChestBlockEntity chest) {
					int slot = 0;
					for (Item seed : seeds) {
						chest.setItem(slot++, new ItemStack(seed, SEEDS_PER_CROP));
					}
				}
				Townsfolk.LOGGER.debug("Placed farm chest at {}", spot);
				return;
			}
		}
	}

	private static @Nullable Item seedFor(Block crop) {
		if (crop == Blocks.WHEAT) {
			return Items.WHEAT_SEEDS;
		}
		if (crop == Blocks.CARROTS) {
			return Items.CARROT;
		}
		if (crop == Blocks.POTATOES) {
			return Items.POTATO;
		}
		if (crop == Blocks.BEETROOTS) {
			return Items.BEETROOT_SEEDS;
		}
		return null;
	}
}
