package com.townsfolk.mixin;

import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerEquipmentContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager AI swaps the main hand item when showing trades or using bone meal, which would delete
 * an item a player gave the villager. Block those swaps while the hand holds a player-given item.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
	private void townsfolk$protectGivenHandItem(EquipmentSlot slot, ItemStack itemStack, CallbackInfo ci) {
		if (slot == EquipmentSlot.MAINHAND
			&& (Object) this instanceof Villager villager
			&& !villager.level().isClientSide()
			&& !villager.isDeadOrDying()
			&& !VillagerEquipmentContainer.WRITING.get()
			&& villager.getAttachedOrElse(ModRegistry.HAND_OWNED, false)) {
			ci.cancel();
		}
	}
}
