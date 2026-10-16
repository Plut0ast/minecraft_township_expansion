package com.townsfolk.mixin;

import com.townsfolk.TownsfolkConfig;
import com.townsfolk.town.RepairPayloads;
import com.townsfolk.town.Sentry;
import com.townsfolk.town.FollowerTravel;
import com.townsfolk.town.TownCapes;
import com.townsfolk.town.TownHiding;
import com.townsfolk.town.TownArrival;
import com.townsfolk.town.TownExile;
import com.townsfolk.town.VillagerFood;
import com.townsfolk.town.VillagerStanding;
import com.townsfolk.work.VillagerWork;
import com.townsfolk.town.VillagerCommands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies stay/follow orders and walks to a new town after the villager's brain has picked where to go,
 * and makes some babies and spawn-egg villagers nitwits (who can become Sentries).
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	/** Chance a baby is a nitwit, by how many of its parents are nitwits (0, 1 or 2): a nitwit parent is needed. */
	private static final float[] NITWIT_BABY_CHANCE = {0.0F, 0.5F, 1.0F};
	/** Chance a villager from a spawn egg is a nitwit. */
	private static final float NITWIT_EGG_CHANCE = 0.2F;

	@Inject(method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;",
		at = @At("RETURN"))
	private void townsfolk$nitwitBaby(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Villager> cir) {
		Villager baby = cir.getReturnValue();
		if (baby == null) {
			return;
		}
		int nitwitParents = (isNitwit((Villager) (Object) this) ? 1 : 0) + (partner instanceof Villager other && isNitwit(other) ? 1 : 0);
		if (TownsfolkConfig.nitwitBabies && level.getRandom().nextFloat() < NITWIT_BABY_CHANCE[nitwitParents]) {
			baby.setVillagerData(baby.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NITWIT));
		}
	}

	/** Villagers from spawn eggs (used or dispensed) are nitwits as often as a baby of two non-nitwits. */
	@Inject(method = "finalizeSpawn", at = @At("TAIL"))
	private void townsfolk$nitwitFromSpawnEgg(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason spawnReason,
		@Nullable SpawnGroupData groupData, CallbackInfoReturnable<SpawnGroupData> cir) {
		Villager villager = (Villager) (Object) this;
		boolean fromEgg = TownsfolkConfig.nitwitSpawnEggs
			&& (spawnReason == EntitySpawnReason.SPAWN_ITEM_USE || spawnReason == EntitySpawnReason.DISPENSER);
		if (fromEgg && villager.getVillagerData().profession().is(VillagerProfession.NONE)
			&& villager.getRandom().nextFloat() < NITWIT_EGG_CHANCE) {
			villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NITWIT));
		}
	}

	/**
	 * Tells the player's game whether this trader repairs, for the Repair button on its trade screen.
	 * Sent before the trade screen opens so the button is there from the start.
	 */
	@Inject(method = "startTrading", at = @At("HEAD"))
	private void townsfolk$offerRepairs(Player player, CallbackInfo ci) {
		RepairPayloads.onStartTrading((Villager) (Object) this, player);
	}

	private static boolean isNitwit(Villager villager) {
		return villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
	}

	@Inject(method = "customServerAiStep", at = @At("TAIL"))
	private void townsfolk$applyCommand(ServerLevel level, CallbackInfo ci) {
		Villager villager = (Villager) (Object) this;
		TownCapes.tickVillager(villager, level);
		FollowerTravel.tick(villager, level);
		if (Sentry.afterBrainTick(villager, level)) {
			return;
		}
		// Mid-meal the hand holds food, so nothing else may use the hand (or lead the villager off) until it is done.
		if (VillagerFood.isEating(villager)) {
			VillagerFood.afterBrainTick(villager, level);
			return;
		}
		if (TownHiding.afterBrainTick(villager, level)) {
			return;
		}
		VillagerCommands.afterBrainTick(villager, level);
		TownArrival.afterBrainTick(villager, level);
		TownExile.afterBrainTick(villager, level);
		VillagerFood.afterBrainTick(villager, level);
		VillagerWork.afterBrainTick(villager, level);
		VillagerStanding.tick(villager, level);
	}
}
