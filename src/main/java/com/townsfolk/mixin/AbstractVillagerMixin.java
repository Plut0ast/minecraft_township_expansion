package com.townsfolk.mixin;

import com.townsfolk.TownsfolkConfig;
import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerMenu;
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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villagers get 3 pockets instead of 8, so every one of them is visible on the villager screen. And each completed
 * trade with a town's villager earns the player reputation with that town.
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/SimpleContainer;<init>(I)V"), index = 0)
	private int townsfolk$morePockets(int size) {
		return VillagerMenu.POCKETS;
	}

	@Inject(method = "notifyTrade", at = @At("TAIL"))
	private void townsfolk$gainReputation(MerchantOffer offer, CallbackInfo ci) {
		if (!((Object) this instanceof Villager villager)
			|| !(villager.level() instanceof ServerLevel level)
			|| !(villager.getTradingPlayer() instanceof ServerPlayer player)) {
			return;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town != null && !TownCenters.isDestroyed(level, town)) {
			TownReputation.get(level).add(town, player.getUUID(), TownsfolkConfig.reputationPerTrade);
		}
	}
}
