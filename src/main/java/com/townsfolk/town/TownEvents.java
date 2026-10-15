package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import com.townsfolk.menu.VillagerMenu;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class TownEvents {
	/** Reputation lost for each hit on one of a town's villagers. */
	private static final int HURT_VILLAGER_PENALTY = -5;
	/** Extra reputation lost for killing one of a town's villagers. */
	private static final int KILL_VILLAGER_PENALTY = -25;

	private TownEvents() {
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof AbstractVillager villager) {
				VillagerNames.ensureNamed(villager);
			} else if (entity instanceof IronGolem golem) {
				VillagerNames.ensureGolemNamed(golem);
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register(TownEvents::onEntityUnload);

		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof TownCenterBlockEntity) {
				TownCenters.track(level, blockEntity.getBlockPos());
			}
		});
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof TownCenterBlockEntity) {
				TownCenters.untrack(level, blockEntity.getBlockPos());
			}
		});

		ServerTickEvents.END_LEVEL_TICK.register(TownCenters::tick);
		ServerTickEvents.END_LEVEL_TICK.register(TownGuards::tick);

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (damageTaken > 0.0F) {
				penalize(entity, source, HURT_VILLAGER_PENALTY);
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> penalize(entity, source, KILL_VILLAGER_PENALTY));

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof AbstractVillager villager) || player.isSpectator() || letVanillaHandle(player.getItemInHand(hand))) {
				return InteractionResult.PASS;
			}
			if (hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer serverPlayer && villager.isAlive()) {
				openVillagerMenu(serverPlayer, villager);
			}
			return InteractionResult.SUCCESS;
		});
	}

	/** Items with their own villager interaction (renaming, leashing, spawn eggs) keep vanilla behaviour. */
	private static boolean letVanillaHandle(ItemStack held) {
		return held.is(Items.NAME_TAG) || held.is(Items.LEAD) || held.is(Items.VILLAGER_SPAWN_EGG) || held.is(Items.WANDERING_TRADER_SPAWN_EGG);
	}

	/** A player (directly or with a projectile) harming a town's villager loses reputation with that town. */
	private static void penalize(LivingEntity victim, DamageSource source, int amount) {
		if (!(victim instanceof Villager villager)
			|| !(villager.level() instanceof ServerLevel level)
			|| !(source.getEntity() instanceof ServerPlayer player)) {
			return;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town != null && !TownCenters.isDestroyed(level, town)) {
			TownReputation.get(level).add(town, player.getUUID(), amount);
		}
	}

	private static void onEntityUnload(Entity entity, ServerLevel level) {
		if (!(entity instanceof Mob member) || !TownCenters.canJoinTown(member)) {
			return;
		}
		Entity.RemovalReason reason = member.getRemovalReason();
		if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
			return;
		}
		BlockPos town = member.getAttached(ModRegistry.TOWN);
		if (town != null && level.isLoaded(town) && level.getBlockEntity(town) instanceof TownCenterBlockEntity townCenter) {
			townCenter.removeResident(member.getUUID());
		}
	}

	private static void openVillagerMenu(ServerPlayer player, AbstractVillager villager) {
		if (player.containerMenu instanceof VillagerMenu open && open.villager() == villager) {
			return;
		}

		Component job;
		boolean canTrade;
		if (villager instanceof Villager v) {
			VillagerData villagerData = v.getVillagerData();
			job = villagerData.profession().value().name();
			canTrade = !villagerData.profession().is(VillagerProfession.NONE) && !villagerData.profession().is(VillagerProfession.NITWIT);
		} else {
			job = villager.getType().getDescription();
			canTrade = true;
		}
		VillagerMenu.Data data = new VillagerMenu.Data(
			villager.getId(),
			VillagerNames.nameOf(villager),
			job,
			canTrade && !villager.isBaby(),
			VillagerCommands.canCommand(player, villager)
		);

		player.openMenu(new ExtendedMenuProvider<VillagerMenu.Data>() {
			@Override
			public VillagerMenu.Data getScreenOpeningData(ServerPlayer p) {
				return data;
			}

			@Override
			public Component getDisplayName() {
				return Component.literal(data.name());
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
				return new VillagerMenu(containerId, inventory, data, villager);
			}
		});
	}
}
