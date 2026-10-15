package com.townsfolk.client.mixin;

import com.townsfolk.client.VillagerArmorLayer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A villager's robe and profession hat are thicker than armour, so they would poke through it.
 * Hide the robe under a chestplate or leggings and the hat under a helmet, like armour replacing clothes.
 */
@Mixin(VillagerModel.class)
public abstract class VillagerModelMixin extends EntityModel<VillagerRenderState> {
	protected VillagerModelMixin(ModelPart root) {
		super(root);
	}

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;)V", at = @At("TAIL"))
	private void townsfolk$hideClothesUnderArmor(VillagerRenderState state, CallbackInfo ci) {
		ZombieVillagerRenderState armor = state.isBaby ? null : state.getData(VillagerArmorLayer.ARMOR);
		boolean bodyArmor = armor != null && (!armor.chestEquipment.isEmpty() || !armor.legsEquipment.isEmpty());
		boolean helmet = armor != null && !armor.headEquipment.isEmpty();

		if (this.root.hasChild("body")) {
			ModelPart body = this.root.getChild("body");
			if (body.hasChild("jacket")) {
				body.getChild("jacket").visible = !bodyArmor;
			}
		}
		if (this.root.hasChild("head")) {
			ModelPart head = this.root.getChild("head");
			if (head.hasChild("hat")) {
				head.getChild("hat").visible = !helmet;
			}
		}
	}
}
