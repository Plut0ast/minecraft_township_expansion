package com.townsfolk.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the daily trade reroll fill a fresh offer list from each level's vanilla trade set. */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerInvoker {
	@Invoker("addOffersFromTradeSet")
	void townsfolk$addOffersFromTradeSet(ServerLevel level, MerchantOffers offers, ResourceKey<TradeSet> resourceKey);
}
