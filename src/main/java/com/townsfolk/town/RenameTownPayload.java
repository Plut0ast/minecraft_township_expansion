package com.townsfolk.town;

import com.townsfolk.Townsfolk;
import com.townsfolk.block.TownCenterBlockEntity;
import com.townsfolk.menu.TownCenterMenu;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Sent from the Town Center screen when a Trusted player saves a new town name. */
public record RenameTownPayload(int containerId, String name) implements CustomPacketPayload {
	public static final Type<RenameTownPayload> TYPE = new Type<>(Townsfolk.id("rename_town"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RenameTownPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, RenameTownPayload::containerId,
		ByteBufCodecs.stringUtf8(TownNames.MAX_LENGTH * 4), RenameTownPayload::name,
		RenameTownPayload::new
	);

	@Override
	public Type<RenameTownPayload> type() {
		return TYPE;
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> handle(payload, context.player()));
	}

	/** Everything is re-checked here: the menu must still be open and the player must still be Trusted. */
	private static void handle(RenameTownPayload payload, ServerPlayer player) {
		if (!(player.containerMenu instanceof TownCenterMenu menu)
			|| menu.containerId != payload.containerId()
			|| !menu.stillValid(player)
			|| !(player.level().getBlockEntity(menu.pos()) instanceof TownCenterBlockEntity town)
			|| !town.isTrustedBy(player)) {
			return;
		}
		String name = TownNames.clean(payload.name());
		if (!name.isEmpty()) {
			town.setTownName(name);
		}
	}
}
