package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import com.townsfolk.town.TownExile;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu for browsing towns. The first town is the one whose Town Center was opened; the rest follow,
 * nearest first. Its only slot is the opened town's banner (changed by Trusted players), followed by
 * the player's inventory.
 */
public class TownCenterMenu extends AbstractContainerMenu {
	public static final int BANNER_X = 8;
	public static final int BANNER_Y = 4;
	public static final int INVENTORY_Y = 144;
	private static final Identifier EMPTY_BANNER_ICON = Identifier.withDefaultNamespace("container/slot/banner");
	public static final int BUTTON_JOIN = 0;
	/** Button ids from here up remove the opened town's villager at (id - this) in {@link #homeVillagerIds()}. */
	public static final int BUTTON_REMOVE_FIRST = 100;

	/** One town as shown in the screen; {@code reputation} is the viewer's reputation with it. */
	/** {@code villagerJobs} lines up with {@code villagers}: a translation key for each villager's job. */
	public record TownView(BlockPos pos, String name, List<String> villagers, List<String> villagerJobs, List<String> golems,
		List<String> players, int reputation, ItemStack banner) {
		public static final StreamCodec<RegistryFriendlyByteBuf, TownView> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TownView::pos,
			ByteBufCodecs.STRING_UTF8, TownView::name,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TownView::villagers,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TownView::villagerJobs,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TownView::golems,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TownView::players,
			ByteBufCodecs.VAR_INT, TownView::reputation,
			ItemStack.OPTIONAL_STREAM_CODEC, TownView::banner,
			TownView::new
		);

		public TownView withName(String name) {
			return new TownView(this.pos, name, this.villagers, this.villagerJobs, this.golems, this.players, this.reputation, this.banner);
		}

		public TownView withoutVillager(int index) {
			List<String> villagers = new ArrayList<>(this.villagers);
			villagers.remove(index);
			List<String> jobs = new ArrayList<>(this.villagerJobs);
			if (index < jobs.size()) {
				jobs.remove(index);
			}
			return new TownView(this.pos, this.name, villagers, jobs, this.golems, this.players, this.reputation, this.banner);
		}

		public TownView withPlayer(String player) {
			List<String> players = new ArrayList<>(this.players);
			players.add(player);
			return new TownView(this.pos, this.name, this.villagers, this.villagerJobs, this.golems, players, this.reputation, this.banner);
		}
	}

	/**
	 * {@code canRename}: the viewer is Trusted by the opened town. {@code canJoin}: the viewer is a Hero
	 * of the opened town and not yet one of its players. {@code canRemove}: the viewer is a Trusted
	 * member and may remove villagers. {@code homeVillagerIds} matches the opened town's villager list.
	 */
	public record Data(List<TownView> towns, boolean canRename, boolean canJoin, boolean canRemove, List<UUID> homeVillagerIds) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			TownView.STREAM_CODEC.apply(ByteBufCodecs.list()), Data::towns,
			ByteBufCodecs.BOOL, Data::canRename,
			ByteBufCodecs.BOOL, Data::canJoin,
			ByteBufCodecs.BOOL, Data::canRemove,
			UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Data::homeVillagerIds,
			Data::new
		);
	}

	private final List<TownView> towns;
	private final boolean canRename;
	private boolean canJoin;
	private final boolean canRemove;
	private final List<UUID> homeVillagerIds;
	/** Client side: false while the screen shows another town, which hides the opened town's banner slot. */
	private boolean showingHome = true;

	/** Client side: the banner's contents arrive through normal slot syncing. */
	public TownCenterMenu(int containerId, Inventory inventory, Data data) {
		this(containerId, inventory, data, new SimpleContainer(1));
	}

	public TownCenterMenu(int containerId, Inventory inventory, Data data, Container banner) {
		super(ModRegistry.TOWN_CENTER_MENU, containerId);
		this.towns = new ArrayList<>(data.towns());
		this.canRename = data.canRename();
		this.canJoin = data.canJoin();
		this.canRemove = data.canRemove();
		this.homeVillagerIds = new ArrayList<>(data.homeVillagerIds());
		this.addSlot(new BannerSlot(banner, data.canRename()));
		this.addStandardInventorySlots(inventory, 8, INVENTORY_Y);
	}

	public void setShowingHome(boolean showingHome) {
		this.showingHome = showingHome;
	}

	/** Takes one banner; only Trusted players may put one in or take it out. */
	private class BannerSlot extends Slot {
		private final boolean editable;

		BannerSlot(Container container, boolean editable) {
			super(container, 0, BANNER_X, BANNER_Y);
			this.editable = editable;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.editable && stack.is(ItemTags.BANNERS);
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.editable && super.mayPickup(player);
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public boolean isActive() {
			return TownCenterMenu.this.showingHome;
		}

		@Override
		public Identifier getNoItemIcon() {
			return EMPTY_BANNER_ICON;
		}
	}

	public boolean canRemove() {
		return this.canRemove;
	}

	public List<UUID> homeVillagerIds() {
		return this.homeVillagerIds;
	}

	/** Client side: drop a removed villager from the opened town's list straight away. */
	public void markRemoved(int index) {
		if (index >= 0 && index < this.homeVillagerIds.size()) {
			this.homeVillagerIds.remove(index);
			this.towns.set(0, this.home().withoutVillager(index));
		}
	}

	/** The town whose Town Center was opened. */
	public TownView home() {
		return this.towns.getFirst();
	}

	public BlockPos pos() {
		return this.home().pos();
	}

	public List<TownView> towns() {
		return this.towns;
	}

	public boolean canRename() {
		return this.canRename;
	}

	public boolean canJoin() {
		return this.canJoin;
	}

	/** Client side: show a saved rename straight away rather than waiting to reopen the menu. */
	public void setTownName(String townName) {
		this.towns.set(0, this.home().withName(townName));
	}

	/** Client side: show the viewer as a player of the opened town straight after joining. */
	public void markJoined(String playerName) {
		this.towns.set(0, this.home().withPlayer(playerName));
		this.canJoin = false;
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (buttonId >= BUTTON_REMOVE_FIRST) {
			return this.removeVillager(player, buttonId - BUTTON_REMOVE_FIRST);
		}
		if (buttonId != BUTTON_JOIN || !this.canJoin) {
			return false;
		}
		// Re-check on the server: only a Hero who is not already a member may join.
		if (!player.level().isClientSide()) {
			if (!(player.level().getBlockEntity(this.pos()) instanceof TownCenterBlockEntity town) || !town.join(player)) {
				return false;
			}
			this.canJoin = false;
		}
		return true;
	}

	/** Server side: re-checks that the viewer is a Trusted member, then removes the villager from the town. */
	private boolean removeVillager(Player player, int index) {
		if (!this.canRemove || index < 0 || index >= this.homeVillagerIds.size()) {
			return false;
		}
		if (player.level() instanceof ServerLevel level) {
			if (!(level.getEntity(this.homeVillagerIds.get(index)) instanceof Villager villager)
				|| !this.pos().equals(villager.getAttached(ModRegistry.TOWN))
				|| !TownExile.canRemove(player, villager)) {
				return false;
			}
			TownExile.remove(villager, level);
			this.markRemoved(index);
		}
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		if (slotIndex == 0) {
			if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			Slot banner = this.slots.getFirst();
			if (banner.hasItem() || !banner.mayPlace(stack) || !this.moveItemStackTo(stack, 0, 1, false)) {
				return ItemStack.EMPTY;
			}
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return stack.getCount() == original.getCount() ? ItemStack.EMPTY : original;
	}

	@Override
	public boolean stillValid(Player player) {
		return player.level().getBlockState(this.pos()).is(ModRegistry.TOWN_CENTER)
			&& player.isWithinBlockInteractionRange(this.pos(), 4.0);
	}
}
