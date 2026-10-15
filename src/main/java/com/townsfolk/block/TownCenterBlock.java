package com.townsfolk.block;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.TownCenters;
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

		TownCenterBlockEntity nearest = TownCenters.nearestOtherTown(serverLevel, pos);
		if (nearest == null) {
			player.sendSystemMessage(Component.translatable("message.townsfolk.town_center.no_town_nearby", TownCenterBlockEntity.CLAIM_RADIUS));
		} else {
			BlockPos other = nearest.getBlockPos();
			int distance = (int) Math.round(Math.sqrt(other.distSqr(pos)));
			player.sendSystemMessage(Component.translatable("message.townsfolk.town_center.town_nearby",
				distance, other.getX(), other.getY(), other.getZ(), nearest.population()));
		}
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
