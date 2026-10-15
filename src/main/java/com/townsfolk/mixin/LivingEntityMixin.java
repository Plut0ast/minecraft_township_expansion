package com.townsfolk.mixin;

import com.townsfolk.ModRegistry;
import com.townsfolk.menu.VillagerEquipmentContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager and wandering trader equipment rules.
 * <p>
 * Their AI swaps the main hand item when showing trades, using bone meal or drinking potions, which would delete
 * an item a player gave the villager. Block those swaps while the hand holds a player-given item.
 * <p>
 * Vanilla only wears down armour on players; their armour takes durability damage the same way,
 * so it protects them and eventually breaks like a player's would.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow
	protected abstract void doHurtEquipment(DamageSource damageSource, float damage, EquipmentSlot... slots);

	@Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
	private void townsfolk$protectGivenHandItem(EquipmentSlot slot, ItemStack itemStack, CallbackInfo ci) {
		if (slot == EquipmentSlot.MAINHAND
			&& (Object) this instanceof AbstractVillager villager
			&& !villager.level().isClientSide()
			&& !villager.isDeadOrDying()
			&& !VillagerEquipmentContainer.WRITING.get()
			&& villager.getAttachedOrElse(ModRegistry.HAND_OWNED, false)) {
			ci.cancel();
		}
	}

	@Inject(method = "hurtArmor", at = @At("HEAD"))
	private void townsfolk$wearVillagerArmor(DamageSource damageSource, float damage, CallbackInfo ci) {
		if ((Object) this instanceof AbstractVillager) {
			this.doHurtEquipment(damageSource, damage, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
		}
	}

	@Inject(method = "hurtHelmet", at = @At("HEAD"))
	private void townsfolk$wearVillagerHelmet(DamageSource damageSource, float damage, CallbackInfo ci) {
		if ((Object) this instanceof AbstractVillager) {
			this.doHurtEquipment(damageSource, damage, EquipmentSlot.HEAD);
		}
	}
}
