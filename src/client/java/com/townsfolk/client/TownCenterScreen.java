package com.townsfolk.client;

import com.townsfolk.menu.TownCenterMenu;
import com.townsfolk.town.RenameTownPayload;
import com.townsfolk.town.TownNames;
import com.townsfolk.town.TownReputation;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * Browses towns: name (renameable at its own Town Center by Trusted players), population, the viewer's
 * reputation, and a scrollable list of players, villagers and golems. Arrows step through other towns,
 * the player button filters to towns with players, and Heroes can join the opened town.
 */
public class TownCenterScreen extends AbstractContainerScreen<TownCenterMenu> {
	private static final int ROW_HEIGHT = 12;
	private static final int LIST_X = 8;
	private static final int LIST_Y = 52;
	private static final int LIST_W = 160;
	private static final int LIST_H = 104;
	private static final int RANK_Y = 20;
	private static final int BAR_Y = 31;
	private static final int NEXT_RANK_Y = 39;
	private static final int NAV_Y = 162;

	// Vanilla chat colours.
	private static final int HEADER_COLOR = 0xFF404040;
	private static final int PLAYER_COLOR = 0xFFFFAA00;
	private static final int VILLAGER_COLOR = 0xFFFFFFFF;
	private static final int GOLEM_COLOR = 0xFFAAAAAA;
	private static final int ENEMY_COLOR = 0xFFAA0000;

	/**
	 * A list line. Headers ("Villagers (5)") are drawn dark without a shadow, like vanilla labels.
	 * {@code villagerIndex} is the row's index in the opened town's villager list, or -1 for other rows.
	 */
	private record Row(Component text, int color, boolean header, int villagerIndex) {
	}

	private static final int REMOVE_X = LIST_X + LIST_W - 16;
	private static final int REMOVE_COLOR = 0xFFFF5555;
	private static final int REMOVE_HOVER_COLOR = 0xFFFFFFFF;

	private int scroll;
	/** Index into {@link #visibleTowns()}. */
	private int index;
	private boolean playersOnly;

	private EditBox nameBox;
	private Button saveButton;
	private Button prevButton;
	private Button nextButton;
	private Button filterButton;
	private Button joinButton;

	public TownCenterScreen(TownCenterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 186);
	}

	private List<TownCenterMenu.TownView> visibleTowns() {
		if (!this.playersOnly) {
			return this.menu.towns();
		}
		List<TownCenterMenu.TownView> withPlayers = new ArrayList<>();
		for (TownCenterMenu.TownView town : this.menu.towns()) {
			if (!town.players().isEmpty()) {
				withPlayers.add(town);
			}
		}
		return withPlayers;
	}

	/** The town on screen, or {@code null} when the player filter leaves none. */
	private TownCenterMenu.TownView current() {
		List<TownCenterMenu.TownView> towns = this.visibleTowns();
		return towns.isEmpty() ? null : towns.get(Mth.clamp(this.index, 0, towns.size() - 1));
	}

	private boolean viewingHome() {
		TownCenterMenu.TownView town = this.current();
		return town != null && town.pos().equals(this.menu.pos());
	}

	private boolean isMemberOfHome() {
		String me = this.minecraft.player.getName().getString();
		return this.menu.home().players().contains(me);
	}

	/** Sections with headings: players (gold), villagers (white), golems (grey). Empty sections are left out. */
	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		TownCenterMenu.TownView town = this.current();
		if (town == null) {
			return rows;
		}
		boolean removable = this.viewingHome() && this.menu.canRemove();
		if (!town.players().isEmpty()) {
			rows.add(header("gui.townsfolk.town_center.players", town.players().size()));
			for (String player : town.players()) {
				rows.add(new Row(Component.literal(player), PLAYER_COLOR, false, -1));
			}
		}
		if (!town.villagers().isEmpty()) {
			rows.add(header("gui.townsfolk.town_center.villagers", town.villagers().size()));
			for (int i = 0; i < town.villagers().size(); i++) {
				rows.add(new Row(Component.literal(town.villagers().get(i)), VILLAGER_COLOR, false, removable ? i : -1));
			}
		}
		if (!town.golems().isEmpty()) {
			rows.add(header("gui.townsfolk.town_center.golems", town.golems().size()));
			for (String golem : town.golems()) {
				rows.add(new Row(Component.literal(golem), GOLEM_COLOR, false, -1));
			}
		}
		return rows;
	}

	private static Row header(String key, int count) {
		return new Row(Component.translatable(key, count), HEADER_COLOR, true, -1);
	}

	@Override
	protected void init() {
		super.init();
		int x = this.leftPos;
		int y = this.topPos;

		this.nameBox = new EditBox(this.font, x + 8, y + 5, 112, 12, Component.translatable("gui.townsfolk.town_center.name"));
		this.nameBox.setMaxLength(TownNames.MAX_LENGTH);
		this.nameBox.setResponder(value -> this.updateSaveButton());
		this.addRenderableWidget(this.nameBox);

		this.saveButton = Button.builder(Component.translatable("gui.townsfolk.town_center.rename"), b -> this.saveName())
			.bounds(x + 124, y + 4, 44, 14)
			.build();
		this.addRenderableWidget(this.saveButton);

		this.prevButton = Button.builder(Component.literal("<"), b -> this.step(-1)).bounds(x + 8, y + NAV_Y, 18, 18).build();
		this.nextButton = Button.builder(Component.literal(">"), b -> this.step(1)).bounds(x + 62, y + NAV_Y, 18, 18).build();
		this.filterButton = Button.builder(Component.literal("☻"), b -> this.togglePlayersOnly())
			.bounds(x + 84, y + NAV_Y, 20, 18)
			.tooltip(Tooltip.create(Component.translatable("gui.townsfolk.town_center.filter_players")))
			.build();
		this.joinButton = Button.builder(Component.translatable("gui.townsfolk.town_center.join"), b -> this.join())
			.bounds(x + 108, y + NAV_Y, 60, 18)
			.build();
		this.addRenderableWidget(this.prevButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(this.filterButton);
		this.addRenderableWidget(this.joinButton);

		this.refreshView();
	}

	/** Updates every widget for the town now on screen. */
	private void refreshView() {
		TownCenterMenu.TownView town = this.current();
		boolean home = this.viewingHome();
		this.scroll = 0;

		this.nameBox.setValue(town == null ? "" : town.name());
		this.nameBox.setEditable(home && this.menu.canRename());
		this.nameBox.setFocused(false);
		this.updateSaveButton();

		int count = this.visibleTowns().size();
		this.prevButton.active = count > 1;
		this.nextButton.active = count > 1;
		this.filterButton.setMessage(this.playersOnly
			? Component.literal("☻").withStyle(ChatFormatting.YELLOW)
			: Component.literal("☻"));

		boolean member = this.isMemberOfHome();
		this.joinButton.setMessage(Component.translatable(home && member ? "gui.townsfolk.town_center.member" : "gui.townsfolk.town_center.join"));
		this.joinButton.active = home && this.menu.canJoin();
		String joinTip = !home ? "gui.townsfolk.town_center.join_elsewhere"
			: member ? "gui.townsfolk.town_center.join_member"
			: this.menu.canJoin() ? "gui.townsfolk.town_center.join_tooltip"
			: "gui.townsfolk.town_center.join_locked";
		this.joinButton.setTooltip(Tooltip.create(Component.translatable(joinTip)));
	}

	private void step(int direction) {
		int count = this.visibleTowns().size();
		if (count > 0) {
			this.index = Math.floorMod(Mth.clamp(this.index, 0, count - 1) + direction, count);
			this.refreshView();
		}
	}

	private void togglePlayersOnly() {
		this.playersOnly = !this.playersOnly;
		this.index = 0;
		this.refreshView();
	}

	private void join() {
		if (!this.viewingHome() || !this.menu.canJoin()) {
			return;
		}
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TownCenterMenu.BUTTON_JOIN);
		this.menu.markJoined(this.minecraft.player.getName().getString());
		this.refreshView();
	}

	private void updateSaveButton() {
		boolean home = this.viewingHome();
		String cleaned = TownNames.clean(this.nameBox.getValue());
		this.saveButton.active = home && this.menu.canRename() && !cleaned.isEmpty() && !cleaned.equals(this.menu.home().name());
		this.saveButton.setTooltip(!home
			? Tooltip.create(Component.translatable("gui.townsfolk.town_center.rename_elsewhere"))
			: this.menu.canRename() ? null : Tooltip.create(Component.translatable("gui.townsfolk.town_center.rename_locked")));
	}

	private void saveName() {
		String cleaned = TownNames.clean(this.nameBox.getValue());
		if (!this.viewingHome() || !this.menu.canRename() || cleaned.isEmpty()) {
			return;
		}
		ClientPlayNetworking.send(new RenameTownPayload(this.menu.containerId, cleaned));
		this.menu.setTownName(cleaned);
		this.nameBox.setValue(cleaned);
		this.nameBox.setFocused(false);
		this.updateSaveButton();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.minecraft.player.closeContainer();
			return true;
		}
		if (this.nameBox.isFocused() && event.isConfirmation()) {
			this.saveName();
			return true;
		}
		// While typing, keys like E must go to the name box instead of closing the screen.
		return this.nameBox.keyPressed(event) || this.nameBox.canConsumeInput() || super.keyPressed(event);
	}

	@Override
	public void resize(int width, int height) {
		String typed = this.nameBox.getValue();
		super.resize(width, height);
		this.nameBox.setValue(typed);
	}

	private int maxScroll() {
		int visibleRows = (LIST_H - 4) / ROW_HEIGHT;
		return Math.max(0, this.rows().size() - visibleRows);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		Panels.window(graphics, x, y, this.imageWidth, this.imageHeight);
		Panels.inset(graphics, x + LIST_X, y + LIST_Y, LIST_W, LIST_H, Panels.SUNKEN_FILL);

		TownCenterMenu.TownView town = this.current();
		if (town != null) {
			this.extractReputationBar(graphics, town.reputation(), x + LIST_X, y + BAR_Y);
		}

		List<Row> rows = this.rows();
		graphics.enableScissor(x + LIST_X + 1, y + LIST_Y + 1, x + LIST_X + LIST_W - 1, y + LIST_Y + LIST_H - 1);
		if (rows.isEmpty()) {
			Component empty = Component.translatable(town == null ? "gui.townsfolk.town_center.no_player_towns" : "gui.townsfolk.town_center.empty");
			graphics.centeredText(this.font, empty, x + LIST_X + LIST_W / 2, y + LIST_Y + LIST_H / 2 - 4, 0xFFE0E0E0);
		}
		for (int i = this.scroll; i < rows.size(); i++) {
			int rowY = y + LIST_Y + 3 + (i - this.scroll) * ROW_HEIGHT;
			if (rowY > y + LIST_Y + LIST_H) {
				break;
			}
			Row row = rows.get(i);
			graphics.text(this.font, row.text(), x + LIST_X + (row.header() ? 3 : 8), rowY, row.color(), !row.header());
			if (row.villagerIndex() >= 0) {
				boolean hovered = this.removeRowAt(mouseX, mouseY) == i;
				graphics.text(this.font, "✕", x + REMOVE_X, rowY, hovered ? REMOVE_HOVER_COLOR : REMOVE_COLOR, true);
			}
		}
		graphics.disableScissor();

		if (this.maxScroll() > 0) {
			int trackH = LIST_H - 2;
			int thumbH = Math.max(10, trackH * (rows.size() - this.maxScroll()) / rows.size());
			int thumbY = y + LIST_Y + 1 + (trackH - thumbH) * this.scroll / this.maxScroll();
			int barX = x + LIST_X + LIST_W - 5;
			graphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFC6C6C6);
		}
	}

	/** Progress towards the next rank; full at the top rank. */
	private void extractReputationBar(GuiGraphicsExtractor graphics, int reputation, int x, int y) {
		TownReputation.Rank rank = TownReputation.Rank.of(reputation);
		TownReputation.Rank next = rank.next();
		float progress = next == null ? 1.0F
			: (float) (reputation - rank.threshold) / (next.threshold - rank.threshold);

		if (rank == TownReputation.Rank.ENEMY) {
			// Enemies fill a red bar the further below zero they sink (full at five times the enemy line).
			int floor = TownReputation.ENEMY_AT * 5;
			progress = (float) (reputation - TownReputation.ENEMY_AT) / (floor - TownReputation.ENEMY_AT);
		}

		Panels.bossBar(graphics, x, y, LIST_W, progress, rank == TownReputation.Rank.ENEMY);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		int count = this.visibleTowns().size();
		Component counter = Component.literal(count == 0 ? "0/0" : (Mth.clamp(this.index, 0, count - 1) + 1) + "/" + count);
		// Vanilla container labels: dark grey, no shadow.
		graphics.text(this.font, counter, 44 - this.font.width(counter) / 2, NAV_Y + 5, Panels.TEXT, false);

		TownCenterMenu.TownView town = this.current();
		if (town == null) {
			return;
		}

		int reputation = town.reputation();
		TownReputation.Rank rank = TownReputation.Rank.of(reputation);
		TownReputation.Rank next = rank.next();
		graphics.text(this.font, Component.translatable("gui.townsfolk.town_center.reputation", rank.displayName(), reputation), LIST_X, RANK_Y,
			rank == TownReputation.Rank.ENEMY ? ENEMY_COLOR : Panels.TEXT, false);

		Component where = this.viewingHome()
			? Component.translatable("gui.townsfolk.town_center.here")
			: Component.translatable("gui.townsfolk.town_center.distance", (int) Math.round(Math.sqrt(town.pos().distSqr(this.menu.pos()))));
		graphics.text(this.font, where, LIST_X + LIST_W - this.font.width(where), RANK_Y, Panels.TEXT_LIGHT, false);

		Component toNext = next == null
			? Component.translatable("gui.townsfolk.town_center.max_rank")
			: Component.translatable("gui.townsfolk.town_center.next_rank", next.threshold - reputation, next.displayName());
		graphics.text(this.font, toNext, LIST_X, NEXT_RANK_Y, Panels.TEXT_LIGHT, false);
	}

	/** The list row whose remove mark is under the mouse, or -1. */
	private int removeRowAt(double mouseX, double mouseY) {
		double localX = mouseX - this.leftPos - REMOVE_X;
		double localY = mouseY - this.topPos - LIST_Y - 2;
		if (localX < -2 || localX > 9 || localY < 0 || localY >= LIST_H - 4) {
			return -1;
		}
		int row = this.scroll + (int) (localY / ROW_HEIGHT);
		List<Row> rows = this.rows();
		return row < rows.size() && rows.get(row).villagerIndex() >= 0 ? row : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int row = this.removeRowAt(event.x(), event.y());
		if (row >= 0 && event.button() == 0) {
			int index = this.rows().get(row).villagerIndex();
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TownCenterMenu.BUTTON_REMOVE_FIRST + index);
			this.menu.markRemoved(index);
			this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);
		if (this.removeRowAt(mouseX, mouseY) >= 0) {
			graphics.setTooltipForNextFrame(this.font, Component.translatable("gui.townsfolk.town_center.remove"), mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (this.maxScroll() > 0) {
			this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY), 0, this.maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}
}
