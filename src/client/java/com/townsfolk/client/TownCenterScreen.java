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
	private static final int LIST_Y = 58;
	private static final int LIST_W = 160;
	private static final int LIST_H = 98;
	private static final int BAR_Y = 42;
	private static final int NAV_Y = 162;

	private static final int PLAYER_COLOR = 0xFFFFD34D;
	private static final int VILLAGER_COLOR = 0xFFFFFFFF;
	private static final int GOLEM_COLOR = 0xFFB8C8D8;

	private record Row(String text, int color) {
	}

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

	/** Players first (gold), then numbered villagers, then golems (steel). */
	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		TownCenterMenu.TownView town = this.current();
		if (town == null) {
			return rows;
		}
		for (String player : town.players()) {
			rows.add(new Row("☻ " + player, PLAYER_COLOR));
		}
		for (int i = 0; i < town.villagers().size(); i++) {
			rows.add(new Row((i + 1) + ". " + town.villagers().get(i), VILLAGER_COLOR));
		}
		for (String golem : town.golems()) {
			rows.add(new Row("⚙ " + golem, GOLEM_COLOR));
		}
		return rows;
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
		Panels.inset(graphics, x + LIST_X, y + LIST_Y, LIST_W, LIST_H, 0xFF8B8B8B);

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
			graphics.text(this.font, row.text(), x + LIST_X + 4, rowY, row.color(), true);
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

		Panels.inset(graphics, x, y, LIST_W, 8, 0xFF373737);
		int filled = Math.round((LIST_W - 2) * Mth.clamp(progress, 0.0F, 1.0F));
		graphics.fill(x + 1, y + 1, x + 1 + filled, y + 7, rank == TownReputation.Rank.ENEMY ? 0xFFC03030 : 0xFF5DBB3F);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		int count = this.visibleTowns().size();
		Component counter = Component.literal(count == 0 ? "0/0" : (Mth.clamp(this.index, 0, count - 1) + 1) + "/" + count);
		graphics.centeredText(this.font, counter, 44, NAV_Y + 5, Panels.TEXT);

		TownCenterMenu.TownView town = this.current();
		if (town == null) {
			return;
		}

		Component population = Component.translatable("gui.townsfolk.town_center.population",
			town.villagers().size(), town.golems().size(), town.players().size());
		graphics.text(this.font, population, LIST_X, 20, Panels.TEXT, false);

		int reputation = town.reputation();
		TownReputation.Rank rank = TownReputation.Rank.of(reputation);
		TownReputation.Rank next = rank.next();
		graphics.text(this.font, Component.translatable("gui.townsfolk.town_center.reputation", rank.displayName(), reputation), LIST_X, 31,
			rank == TownReputation.Rank.ENEMY ? 0xFFA02020 : Panels.TEXT, false);
		Component toNext = next == null
			? Component.translatable("gui.townsfolk.town_center.max_rank")
			: Component.translatable("gui.townsfolk.town_center.next_rank", next.threshold - reputation, next.displayName());
		graphics.text(this.font, toNext, LIST_X + LIST_W - this.font.width(toNext), 20, 0xFF606060, false);

		Component where = this.viewingHome()
			? Component.translatable("gui.townsfolk.town_center.here")
			: Component.translatable("gui.townsfolk.town_center.distance", (int) Math.round(Math.sqrt(town.pos().distSqr(this.menu.pos()))));
		graphics.text(this.font, where, LIST_X + LIST_W - this.font.width(where), 31, 0xFF606060, false);
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
