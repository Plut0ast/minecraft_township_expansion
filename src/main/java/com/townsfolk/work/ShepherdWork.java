package com.townsfolk.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.ModRegistry;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Shepherds look after their own small flock. The first time one works it gets a fenced pen near its
 * loom with three sheep and a chest; after that it shears its own sheep from the pen gate and keeps the
 * wool in that chest. Lost sheep are not replaced.
 */
public final class ShepherdWork {
	/** The shepherd's pen: where to stand (just outside the gate) and its chest. */
	public record Pen(BlockPos gateFront, BlockPos chest) {
		public static final Codec<Pen> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("gate_front").forGetter(Pen::gateFront),
			BlockPos.CODEC.fieldOf("chest").forGetter(Pen::chest)
		).apply(i, Pen::new));
	}

	private static final int FLOCK_SIZE = 3;
	/** The pen is 5x5 fences around a 3x3 pasture. */
	private static final int PEN_HALF = 2;
	private static final double SHEAR_REACH = 4.6;
	private static final int SHEAR_COOLDOWN_TICKS = 60;
	/** If there is nowhere to build a pen yet, try again after this long. */
	private static final int RETRY_TICKS = 1200;

	private static final Map<Villager, Long> NEXT_TRY = new WeakHashMap<>();
	private static final Map<Villager, Long> NEXT_SHEAR = new WeakHashMap<>();

	private ShepherdWork() {
	}

	public static boolean isShepherd(Villager villager) {
		return WorkHelper.is(villager, VillagerProfession.SHEPHERD);
	}

	public static void tick(Villager villager, ServerLevel level, BlockPos loom) {
		Pen pen = villager.getAttached(ModRegistry.SHEPHERD_PEN);
		if (pen == null) {
			if (level.getGameTime() >= NEXT_TRY.getOrDefault(villager, 0L)) {
				NEXT_TRY.put(villager, level.getGameTime() + RETRY_TICKS);
				setUp(villager, level, loom);
			}
			return;
		}

		// Wool goes to the chest.
		if (WorkHelper.pocketsHave(villager, stack -> stack.is(ItemTags.WOOL))) {
			if (WorkHelper.walkTo(villager, pen.chest(), WorkHelper.REACH)) {
				Container chest = WorkHelper.container(level, pen.chest());
				if (chest != null) {
					WorkHelper.unload(villager, chest, stack -> stack.is(ItemTags.WOOL));
					level.playSound(null, pen.chest(), SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
				}
			}
			return;
		}

		Sheep woolly = level.getEntitiesOfClass(Sheep.class, new AABB(loom).inflate(32),
				sheep -> villager.getUUID().equals(sheep.getAttached(ModRegistry.FLOCK_OWNER)) && sheep.readyForShearing())
			.stream().min(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(villager))).orElse(null);
		if (woolly == null) {
			return;
		}
		if (!WorkHelper.walkTo(villager, pen.gateFront(), 1.2) || villager.distanceTo(woolly) > SHEAR_REACH) {
			return;
		}
		long now = level.getGameTime();
		if (now < NEXT_SHEAR.getOrDefault(villager, 0L)) {
			return;
		}
		NEXT_SHEAR.put(villager, now + SHEAR_COOLDOWN_TICKS);
		woolly.shear(level, SoundSource.NEUTRAL, new ItemStack(Items.SHEARS));
		WorkHelper.workSound(villager, level);
		// Gather the wool that just fell.
		for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, woolly.getBoundingBox().inflate(3), e -> e.getItem().is(ItemTags.WOOL))) {
			ItemStack left = WorkHelper.pocket(villager, drop.getItem().copy());
			if (left.isEmpty()) {
				drop.discard();
			} else {
				drop.setItem(left);
			}
		}
	}

	/** Builds the pen, places the chest by the loom and brings in the flock. */
	private static void setUp(Villager villager, ServerLevel level, BlockPos loom) {
		BlockPos center = findPenSpot(level, loom);
		if (center == null) {
			return; // no flat, open ground nearby yet
		}
		Direction toLoom = Direction.getApproximateNearest(loom.getX() - center.getX(), 0, loom.getZ() - center.getZ());
		if (toLoom.getAxis() == Direction.Axis.Y) {
			toLoom = Direction.NORTH;
		}
		Block fence = fenceFor(villager);
		Block gate = gateFor(villager);
		for (int dx = -PEN_HALF; dx <= PEN_HALF; dx++) {
			for (int dz = -PEN_HALF; dz <= PEN_HALF; dz++) {
				if (Math.abs(dx) == PEN_HALF || Math.abs(dz) == PEN_HALF) {
					level.setBlockAndUpdate(center.offset(dx, 0, dz), fence.defaultBlockState());
				}
			}
		}
		BlockPos gatePos = center.relative(toLoom, PEN_HALF);
		level.setBlockAndUpdate(gatePos, gate.defaultBlockState().setValue(FenceGateBlock.FACING, toLoom));
		BlockPos gateFront = gatePos.relative(toLoom);

		BlockPos chest = placeChest(level, loom);
		if (chest == null) {
			chest = gateFront.relative(toLoom.getClockWise());
			level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, toLoom));
		}

		for (int i = 0; i < FLOCK_SIZE; i++) {
			Sheep sheep = EntityTypes.SHEEP.create(level, EntitySpawnReason.TRIGGERED);
			if (sheep == null) {
				continue;
			}
			BlockPos spot = center.offset(i - 1, 0, 0);
			sheep.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, villager.getRandom().nextFloat() * 360.0F, 0.0F);
			sheep.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.TRIGGERED, null);
			sheep.setPersistenceRequired();
			sheep.setAttached(ModRegistry.FLOCK_OWNER, villager.getUUID());
			level.addFreshEntity(sheep);
		}
		villager.setAttached(ModRegistry.SHEPHERD_PEN, new Pen(gateFront, chest));
		WorkHelper.workSound(villager, level);
	}

	/** A flat 5x5 patch of open ground near the loom, closest first. */
	private static @Nullable BlockPos findPenSpot(ServerLevel level, BlockPos loom) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -10; dx <= 10; dx++) {
			for (int dz = -10; dz <= 10; dz++) {
				if (Math.abs(dx) < 4 && Math.abs(dz) < 4) {
					continue; // leave room around the loom
				}
				BlockPos column = loom.offset(dx, 0, dz);
				BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
				if (Math.abs(center.getY() - loom.getY()) > 3) {
					continue;
				}
				double dist = center.distSqr(loom);
				if (dist < bestDist && isOpenFlat(level, center)) {
					best = center;
					bestDist = dist;
				}
			}
		}
		return best;
	}

	private static boolean isOpenFlat(ServerLevel level, BlockPos center) {
		for (int dx = -PEN_HALF; dx <= PEN_HALF; dx++) {
			for (int dz = -PEN_HALF; dz <= PEN_HALF; dz++) {
				BlockPos pos = center.offset(dx, 0, dz);
				BlockPos below = pos.below();
				if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()
					|| !level.getBlockState(pos.above()).canBeReplaced()
					|| !level.getFluidState(below).isEmpty()
					|| !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
					return false;
				}
			}
		}
		return true;
	}

	private static @Nullable BlockPos placeChest(ServerLevel level, BlockPos loom) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos pos = loom.relative(direction);
			BlockPos below = pos.below();
			if (level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty()
				&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
				level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, direction));
				return pos;
			}
		}
		return null;
	}

	/** Fences match the village's wood, like vanilla village pens. */
	private static Block fenceFor(Villager villager) {
		var type = villager.getVillagerData().type();
		if (type.is(VillagerType.TAIGA) || type.is(VillagerType.SNOW)) {
			return Blocks.SPRUCE_FENCE;
		}
		if (type.is(VillagerType.SAVANNA)) {
			return Blocks.ACACIA_FENCE;
		}
		if (type.is(VillagerType.JUNGLE)) {
			return Blocks.JUNGLE_FENCE;
		}
		return Blocks.OAK_FENCE;
	}

	private static Block gateFor(Villager villager) {
		var type = villager.getVillagerData().type();
		if (type.is(VillagerType.TAIGA) || type.is(VillagerType.SNOW)) {
			return Blocks.SPRUCE_FENCE_GATE;
		}
		if (type.is(VillagerType.SAVANNA)) {
			return Blocks.ACACIA_FENCE_GATE;
		}
		if (type.is(VillagerType.JUNGLE)) {
			return Blocks.JUNGLE_FENCE_GATE;
		}
		return Blocks.OAK_FENCE_GATE;
	}
}
