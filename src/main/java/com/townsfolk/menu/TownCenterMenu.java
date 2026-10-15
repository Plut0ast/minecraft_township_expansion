package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Read-only menu listing the residents of a town and the viewer's reputation with it. It has no slots. */
public class TownCenterMenu extends AbstractContainerMenu {
	/** {@code reputation} is the viewing player's reputation with this town. */
	public record Data(BlockPos pos, List<String> residents, int reputation) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Data::pos,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Data::residents,
			ByteBufCodecs.VAR_INT, Data::reputation,
			Data::new
		);
	}

	private final Data data;

	public TownCenterMenu(int containerId, Inventory inventory, Data data) {
		super(ModRegistry.TOWN_CENTER_MENU, containerId);
		this.data = data;
	}

	public List<String> residents() {
		return this.data.residents();
	}

	public int reputation() {
		return this.data.reputation();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return player.level().getBlockState(this.data.pos()).is(ModRegistry.TOWN_CENTER)
			&& player.isWithinBlockInteractionRange(this.data.pos(), 4.0);
	}
}
