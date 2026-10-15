package com.townsfolk.work;

import com.townsfolk.TownsfolkConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Daily work for villagers with a profession, done automatically by day at or around their
 * workstation, the way vanilla villagers "work" there. (Farmers farm through their Farming order.)
 */
public final class VillagerWork {
	private VillagerWork() {
	}

	/** Runs right after the villager's brain each tick, after everything more important. */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		if (!WorkHelper.canWork(villager, level)) {
			return;
		}
		// Worn gear first: off to the right smith for a free repair.
		if (TownsfolkConfig.repairVisits && SmithWork.visitForRepair(villager, level)) {
			return;
		}
		BlockPos site = WorkHelper.jobSite(villager, level);
		if (site == null || !TownsfolkConfig.villagerJobs) {
			return;
		}
		if (FishermanWork.isFisherman(villager)) {
			if (TownsfolkConfig.fishermen) {
				FishermanWork.tick(villager, level, site);
			}
		} else if (ShepherdWork.isShepherd(villager)) {
			if (TownsfolkConfig.shepherds) {
				ShepherdWork.tick(villager, level, site);
			}
		} else if (ButcherWork.isButcher(villager)) {
			if (TownsfolkConfig.butchers) {
				ButcherWork.tick(villager, level, site);
			}
		} else if (ClericWork.isCleric(villager)) {
			if (TownsfolkConfig.clerics) {
				ClericWork.tick(villager, level, site);
			}
		} else if (FletcherWork.isFletcher(villager)) {
			if (TownsfolkConfig.fletchers) {
				FletcherWork.tick(villager, level, site);
			}
		} else if (SmithWork.isSmith(villager)) {
			if (TownsfolkConfig.smiths) {
				SmithWork.tick(villager, level, site);
			}
		}
	}
}
