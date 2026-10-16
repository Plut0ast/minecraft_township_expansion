package com.townsfolk.mixin;

import com.townsfolk.ModRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ReputationEventHandler;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers who cured a zombie villager, so they earn reputation with the town it joins. Vanilla
 * reports the cure through this reputation event.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "onReputationEvent", at = @At("HEAD"))
	private void townsfolk$rememberCurer(ReputationEventType type, Entity source, ReputationEventHandler target, CallbackInfo ci) {
		if (type == ReputationEventType.ZOMBIE_VILLAGER_CURED && source instanceof Player player && target instanceof Villager villager) {
			villager.setAttached(ModRegistry.CURED_BY, player.getUUID());
		}
	}
}
