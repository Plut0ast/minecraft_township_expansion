package com.townsfolk.mixin;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.TownCenters;
import com.townsfolk.town.TownReputation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each completed trade with a town's villager earns the player reputation with that town. */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
	@Inject(method = "notifyTrade", at = @At("TAIL"))
	private void townsfolk$gainReputation(MerchantOffer offer, CallbackInfo ci) {
		if (!((Object) this instanceof Villager villager)
			|| !(villager.level() instanceof ServerLevel level)
			|| !(villager.getTradingPlayer() instanceof ServerPlayer player)) {
			return;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town != null && !TownCenters.isDestroyed(level, town)) {
			TownReputation.get(level).add(town, player.getUUID(), 1);
		}
	}
}
