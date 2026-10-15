package com.townsfolk.town;

import com.townsfolk.Townsfolk;
import com.townsfolk.menu.VillagerMenu;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

/** Sent from the villager screen when a player who is a Friend of the villager's town renames it. */
public record RenameVillagerPayload(int containerId, String name) implements CustomPacketPayload {
	public static final Type<RenameVillagerPayload> TYPE = new Type<>(Townsfolk.id("rename_villager"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RenameVillagerPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, RenameVillagerPayload::containerId,
		ByteBufCodecs.stringUtf8(TownNames.MAX_LENGTH * 4), RenameVillagerPayload::name,
		RenameVillagerPayload::new
	);

	@Override
	public Type<RenameVillagerPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> handle(payload, context.player()));
	}

	/** Re-checked here: the villager's screen must still be open and the player must still be a Friend. */
	private static void handle(RenameVillagerPayload payload, ServerPlayer player) {
		if (!(player.containerMenu instanceof VillagerMenu menu)
			|| menu.containerId != payload.containerId()
			|| !menu.stillValid(player)
			|| !(menu.villager() instanceof AbstractVillager villager)
			|| !VillagerCommands.canRename(player, villager)) {
			return;
		}
		String name = TownNames.clean(payload.name());
		if (!name.isEmpty()) {
			villager.setCustomName(Component.literal(name));
		}
	}
}
