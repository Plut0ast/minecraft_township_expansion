package com.townsfolk.mixin;

import com.townsfolk.town.TownBellGlow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player ringing a bell lights up the town's Town Center. */
@Mixin(BellBlock.class)
public abstract class BellBlockMixin {
	@Inject(
		method = "attemptToRing(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
		at = @At("RETURN")
	)
	private void townsfolk$glowTownCenter(@Nullable Entity ringer, Level level, BlockPos pos, @Nullable Direction direction,
		CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && ringer instanceof Player && level instanceof ServerLevel serverLevel) {
			TownBellGlow.onBellRung(serverLevel, pos);
		}
	}
}
