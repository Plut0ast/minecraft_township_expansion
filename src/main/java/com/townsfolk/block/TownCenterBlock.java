package com.townsfolk.block;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.TownCenters;
import com.townsfolk.town.TownRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

public class TownCenterBlock extends BaseEntityBlock {
	public TownCenterBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TownCenterBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModRegistry.TOWN_CENTER_BLOCK_ENTITY, TownCenterBlockEntity::serverTick);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
		super.setPlacedBy(level, pos, state, by, itemStack);
		if (!(level instanceof ServerLevel serverLevel) || !(by instanceof ServerPlayer player)) {
			return;
		}

		if (!(level.getBlockEntity(pos) instanceof TownCenterBlockEntity town)) {
			return;
		}
		// Players of a town add Town Centers to it; founding a new town means leaving yours first.
		BlockPos current = TownRegistry.get(serverLevel).townOf(player.getUUID());
		if (current != null && !current.equals(pos) && serverLevel.getBlockEntity(current) instanceof TownCenterBlockEntity main && !main.isBranch()) {
			town.attachTo(current);
			player.sendSystemMessage(Component.translatable("message.townsfolk.town_center.branch", main.townName()), true);
			return;
		}
		String elsewhere = townNameElsewhere(serverLevel, player);
		if (elsewhere != null) {
			// A player of a town in another dimension: not founded here; the block goes back to them.
			serverLevel.removeBlock(pos, false);
			if (!player.isCreative()) {
				ItemStack refund = itemStack.copyWithCount(1);
				if (!player.getInventory().add(refund)) {
					player.spawnAtLocation(serverLevel, refund);
				}
			}
			player.sendSystemMessage(Component.translatable("message.townsfolk.town_center.leave_first", elsewhere), true);
			return;
		}
		String left = town.found(player);
		String founded = town.townName();

		// Shown above the hotbar rather than in chat.
		TownCenterBlockEntity nearest = TownCenters.nearestOtherTown(serverLevel, pos);
		Component message;
		if (nearest == null) {
			message = Component.translatable("message.townsfolk.town_center.founded_alone", founded);
		} else {
			int distance = (int) Math.round(Math.sqrt(nearest.getBlockPos().distSqr(pos)));
			message = Component.translatable("message.townsfolk.town_center.founded_near", founded, nearest.townName(), distance);
		}
		if (!left.isEmpty()) {
			message = Component.translatable("message.townsfolk.town_center.founded_left", message, left);
		}
		player.sendSystemMessage(message, true);
	}

	/** The name of a town the player belongs to in another dimension, or null. */
	private static @Nullable String townNameElsewhere(ServerLevel here, ServerPlayer player) {
		for (ServerLevel level : here.getServer().getAllLevels()) {
			if (level == here) {
				continue;
			}
			TownRegistry registry = TownRegistry.get(level);
			BlockPos town = registry.townOf(player.getUUID());
			if (town != null) {
				TownRegistry.Town data = registry.town(town);
				return data == null ? "" : data.name();
			}
		}
		return null;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof TownCenterBlockEntity townCenter) {
			townCenter.refreshResidents();
			player.openMenu(townCenter);
		}
		return InteractionResult.SUCCESS;
	}
}
