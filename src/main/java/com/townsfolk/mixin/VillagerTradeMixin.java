package com.townsfolk.mixin;

import com.townsfolk.town.VillagerStanding;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks for {@link VillagerStanding}: per-player trade views and standing instead of villager xp. */
@Mixin(Villager.class)
public abstract class VillagerTradeMixin {
	@Shadow
	private int villagerXp;
	@Shadow
	private @Nullable Player lastTradedPlayer;

	@Inject(method = "startTrading", at = @At("HEAD"))
	private void townsfolk$beginTrading(Player player, CallbackInfo ci) {
		int[] xp = {this.villagerXp};
		VillagerStanding.beginTrading((Villager) (Object) this, player, xp);
		this.villagerXp = xp[0];
	}

	@Inject(method = "rewardTradeXp", at = @At("HEAD"), cancellable = true)
	private void townsfolk$rewardStanding(MerchantOffer offer, CallbackInfo ci) {
		Villager villager = (Villager) (Object) this;
		int[] xp = {this.villagerXp};
		Player trader = villager.getTradingPlayer();
		if (VillagerStanding.rewardTrade(villager, offer, xp)) {
			this.villagerXp = xp[0];
			this.lastTradedPlayer = trader; // vanilla uses this for the villager's own gossip about the trade
			ci.cancel();
		}
	}

	@Inject(method = "setTradingPlayer", at = @At("HEAD"))
	private void townsfolk$endTrading(@Nullable Player player, CallbackInfo ci) {
		if (player == null) {
			int[] xp = {this.villagerXp};
			VillagerStanding.endTrading((Villager) (Object) this, xp);
			this.villagerXp = xp[0];
		}
	}

	@Inject(method = "addAdditionalSaveData", at = @At("HEAD"))
	private void townsfolk$saveRealTrades(ValueOutput output, CallbackInfo ci) {
		int[] xp = {this.villagerXp};
		VillagerStanding.beforeSave((Villager) (Object) this, xp);
		this.villagerXp = xp[0];
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void townsfolk$restoreView(ValueOutput output, CallbackInfo ci) {
		int[] xp = {this.villagerXp};
		VillagerStanding.afterSave((Villager) (Object) this, xp);
		this.villagerXp = xp[0];
	}
}
