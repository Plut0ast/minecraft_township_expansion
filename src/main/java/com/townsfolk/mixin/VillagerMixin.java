package com.townsfolk.mixin;

import com.townsfolk.town.VillagerCommands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies stay/follow orders after the villager's brain has picked where it wants to go. */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	@Inject(method = "customServerAiStep", at = @At("TAIL"))
	private void townsfolk$applyCommand(ServerLevel level, CallbackInfo ci) {
		VillagerCommands.afterBrainTick((Villager) (Object) this, level);
	}
}
