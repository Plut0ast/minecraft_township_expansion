package com.townsfolk.town;

import com.townsfolk.Townsfolk;
import com.townsfolk.menu.RepairMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * The Repair button on smiths' trade screens. When trading starts the server tells the client whether
 * this villager repairs ({@link Trader}); the client's Repair button asks to open the repair menu
 * ({@link Open}).
 */
public final class RepairPayloads {
	private RepairPayloads() {
	}

	/** Server to client: the trader the player just opened, or -1 if it does not repair. */
	public record Trader(int villagerId) implements CustomPacketPayload {
		public static final Type<Trader> TYPE = new Type<>(Townsfolk.id("repair_trader"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Trader> STREAM_CODEC =
			ByteBufCodecs.VAR_INT.map(Trader::new, Trader::villagerId).cast();

		@Override
		public Type<Trader> type() {
			return TYPE;
		}
	}

	/** Client to server: open the repair menu for this smith. */
	public record Open(int villagerId) implements CustomPacketPayload {
		public static final Type<Open> TYPE = new Type<>(Townsfolk.id("open_repair"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC =
			ByteBufCodecs.VAR_INT.map(Open::new, Open::villagerId).cast();

		@Override
		public Type<Open> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Trader.TYPE, Trader.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Open.TYPE, Open.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Open.TYPE, (payload, context) -> open(context.player(), payload.villagerId()));
	}

	/** Called when a player starts trading with a villager. */
	public static void onStartTrading(Villager villager, Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			boolean repairs = RepairMenu.Kind.of(villager) != null && !villager.isBaby();
			ServerPlayNetworking.send(serverPlayer, new Trader(repairs ? villager.getId() : -1));
		}
	}

	private static void open(ServerPlayer player, int villagerId) {
		if (!(player.level().getEntity(villagerId) instanceof Villager smith) || !smith.isAlive() || player.distanceToSqr(smith) > 64.0) {
			return;
		}
		RepairMenu.Kind kind = RepairMenu.Kind.of(smith);
		if (kind == null) {
			return;
		}
		smith.setTradingPlayer(null);
		Component name = smith.getDisplayName();
		RepairMenu.Data data = new RepairMenu.Data(smith.getId(), kind.ordinal(), name);
		player.openMenu(new ExtendedMenuProvider<RepairMenu.Data>() {
			@Override
			public RepairMenu.Data getScreenOpeningData(ServerPlayer p) {
				return data;
			}

			@Override
			public Component getDisplayName() {
				return name;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
				return new RepairMenu(containerId, inventory, data, smith);
			}
		});
	}
}
