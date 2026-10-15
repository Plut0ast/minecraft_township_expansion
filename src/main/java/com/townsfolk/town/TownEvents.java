package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import com.townsfolk.menu.VillagerMenu;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class TownEvents {
	private TownEvents() {
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Villager villager) {
				VillagerNames.ensureNamed(villager);
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

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof Villager villager) || player.isSpectator() || letVanillaHandle(player.getItemInHand(hand))) {
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
		return held.is(Items.NAME_TAG) || held.is(Items.LEAD) || held.is(Items.VILLAGER_SPAWN_EGG);
	}

	private static void onEntityUnload(Entity entity, ServerLevel level) {
		if (!(entity instanceof Villager villager)) {
			return;
		}
		Entity.RemovalReason reason = villager.getRemovalReason();
		if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
			return;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town != null && level.isLoaded(town) && level.getBlockEntity(town) instanceof TownCenterBlockEntity townCenter) {
			townCenter.removeResident(villager.getUUID());
		}
	}

	private static void openVillagerMenu(ServerPlayer player, Villager villager) {
		if (player.containerMenu instanceof VillagerMenu open && open.villager() == villager) {
			return;
		}

		VillagerData villagerData = villager.getVillagerData();
		boolean hasJob = !villagerData.profession().is(VillagerProfession.NONE) && !villagerData.profession().is(VillagerProfession.NITWIT);
		VillagerMenu.Data data = new VillagerMenu.Data(
			villager.getId(),
			VillagerNames.nameOf(villager),
			villagerData.profession().value().name(),
			hasJob && !villager.isBaby()
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
