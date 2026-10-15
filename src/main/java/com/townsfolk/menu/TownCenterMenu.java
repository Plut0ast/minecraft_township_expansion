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
	/**
	 * {@code reputation} is the viewing player's reputation with this town; {@code canRename} is
	 * whether that reputation is high enough (Trusted) to rename it.
	 */
	public record Data(BlockPos pos, String townName, List<String> residents, List<String> golems, int reputation, boolean canRename) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Data::pos,
			ByteBufCodecs.STRING_UTF8, Data::townName,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Data::residents,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Data::golems,
			ByteBufCodecs.VAR_INT, Data::reputation,
			ByteBufCodecs.BOOL, Data::canRename,
			Data::new
		);
	}

	private final Data data;
	private String townName;

	public TownCenterMenu(int containerId, Inventory inventory, Data data) {
		super(ModRegistry.TOWN_CENTER_MENU, containerId);
		this.data = data;
		this.townName = data.townName();
	}

	public BlockPos pos() {
		return this.data.pos();
	}

	public String townName() {
		return this.townName;
	}

	/** Client side: show a saved rename straight away rather than waiting to reopen the menu. */
	public void setTownName(String townName) {
		this.townName = townName;
	}

	public boolean canRename() {
		return this.data.canRename();
	}

	public List<String> residents() {
		return this.data.residents();
	}

	public List<String> golems() {
		return this.data.golems();
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
