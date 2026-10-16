package com.townsfolk.mixin;

import com.townsfolk.town.TownDiscounts;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Heroes of a town get cheaper trades from its villagers (see {@link TownDiscounts}). */
@Mixin(Villager.class)
public abstract class VillagerTradeMixin {
	@Inject(method = "updateSpecialPrices", at = @At("TAIL"))
	private void townsfolk$heroDiscount(Player player, CallbackInfo ci) {
		TownDiscounts.apply((Villager) (Object) this, player);
	}
}
