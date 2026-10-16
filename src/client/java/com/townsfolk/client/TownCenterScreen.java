package com.townsfolk.client;

import com.townsfolk.menu.TownCenterMenu;
import com.townsfolk.town.RenameTownPayload;
import com.townsfolk.town.TownNames;
import com.townsfolk.town.TownRelations;
import com.townsfolk.town.TownReputation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Browses towns: name (renameable at its own Town Center by Trusted players), population, the viewer's
 * reputation, and a scrollable list of players, villagers and golems. Arrows step through other towns,
 * the player button filters to towns with players, and Heroes can join the opened town.
 */
public class TownCenterScreen extends AbstractContainerScreen<TownCenterMenu> {
	private static final int ROW_HEIGHT = 12;
	private static final int LIST_X = 8;
	private static final int LIST_Y = 54;
	private static final int LIST_W = 160;
	private static final int LIST_H = 52;
	private static final int RANK_Y = 23;
	private static final int BAR_Y = 34;
	private static final int NEXT_RANK_Y = 42;
	private static final int NAV_Y = 110;
	/** The name sits right of the banner slot. */
	private static final int NAME_X = 28;

	private static final int HEADER_COLOR = 0xFF404040;
	private static final int NAME_COLOR = 0xFFFFFFFF;
	private static final int JOB_COLOR = 0xFFAAAAAA;
	private static final int HEADER_HOVER_FILL = 0x30FFFFFF;

	/**
	 * A list line. Headers ("Villagers (5)") are drawn dark without a shadow, like vanilla labels, and
	 * fold their section away when clicked; {@code section} is the header's key (null for other rows).
	 * {@code villagerIndex} is the row's index in the opened town's villager list, or -1 for other rows.
	 * {@code job} is shown on the right of villager rows (null for other rows).
	 */
	private record Row(Component text, int color, boolean header, int villagerIndex, @Nullable Component job, @Nullable String section) {
	}

	private static final String SENTRY_JOB = "gui.townsfolk.villager.sentry";
	/** Sections folded away; kept while the game runs so they stay folded the next time a Town Center is opened. */
	private static final Set<String> COLLAPSED = new HashSet<>();

	private static final int REMOVE_X = LIST_X + LIST_W - 16;
	private static final int REMOVE_COLOR = 0xFFAAAAAA;
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
	/** In Join's place while viewing another town: how the opened town treats it. */
	private Button relationButton;

	public TownCenterScreen(TownCenterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, TownCenterMenu.INVENTORY_Y + 83);
		this.titleLabelX = NAME_X;
		this.inventoryLabelY = TownCenterMenu.INVENTORY_Y - 11;
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

	/**
	 * Sections with headings for players, villagers, Sentries and golems. Empty sections are left out,
	 * and a folded section shows only its heading.
	 */
	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		TownCenterMenu.TownView town = this.current();
		if (town == null) {
			return rows;
		}
		boolean removable = this.viewingHome() && this.menu.canRemove();

		List<Row> players = new ArrayList<>();
		for (String player : town.players()) {
			players.add(new Row(Component.literal(player), NAME_COLOR, false, -1, null, null));
		}
		// Sentries are listed on their own, like golems; everyone else under Villagers with their job.
		List<Row> villagers = new ArrayList<>();
		List<Row> sentries = new ArrayList<>();
		for (int i = 0; i < town.villagers().size(); i++) {
			String jobKey = i < town.villagerJobs().size() ? town.villagerJobs().get(i) : "";
			Component name = Component.literal(town.villagers().get(i));
			if (jobKey.equals(SENTRY_JOB)) {
				sentries.add(new Row(name, NAME_COLOR, false, removable ? i : -1, null, null));
			} else {
				villagers.add(new Row(name, NAME_COLOR, false, removable ? i : -1, jobKey.isEmpty() ? null : Component.translatable(jobKey), null));
			}
		}
		List<Row> golems = new ArrayList<>();
		for (String golem : town.golems()) {
			golems.add(new Row(Component.literal(golem), NAME_COLOR, false, -1, null, null));
		}

		addSection(rows, "gui.townsfolk.town_center.players", players);
		addSection(rows, "gui.townsfolk.town_center.villagers", villagers);
		addSection(rows, "gui.townsfolk.town_center.sentries", sentries);
		addSection(rows, "gui.townsfolk.town_center.golems", golems);
		return rows;
	}

	private static void addSection(List<Row> rows, String key, List<Row> members) {
		if (members.isEmpty()) {
			return;
		}
		boolean folded = COLLAPSED.contains(key);
		// Clicking the heading folds or unfolds it; a small pixel arrow (drawn with the list) shows which.
		rows.add(new Row(Component.translatable(key, members.size()), HEADER_COLOR, true, -1, null, key));
		if (!folded) {
			rows.addAll(members);
		}
	}

	/**
	 * A small pixel arrow, like vanilla's: pointing right (4 x 7 pixels) when the section is folded,
	 * pointing down (7 x 4) when it is open. {@code top} is the top of the text line.
	 */
	private static void extractArrow(GuiGraphicsExtractor graphics, int left, int top, boolean folded) {
		for (int i = 0; i < 4; i++) {
			if (folded) {
				// Column i spans rows i..6-i.
				graphics.fill(left + i, top + i, left + i + 1, top + 7 - i, HEADER_COLOR);
			} else {
				// Row i spans columns i..6-i.
				graphics.fill(left + i, top + 1 + i, left + 7 - i, top + 2 + i, HEADER_COLOR);
			}
		}
	}

	/** The list row under the mouse, or -1. */
	private int rowAt(double mouseX, double mouseY) {
		double localX = mouseX - this.leftPos - LIST_X;
		double localY = mouseY - this.topPos - LIST_Y - 2;
		if (localX < 0 || localX >= LIST_W - 6 || localY < 0 || localY >= LIST_H - 4) {
			return -1;
		}
		int row = this.scroll + (int) (localY / ROW_HEIGHT);
		return row < this.rows().size() ? row : -1;
	}

	@Override
	protected void init() {
		super.init();
		int x = this.leftPos;
		int y = this.topPos;

		this.nameBox = new EditBox(this.font, x + NAME_X, y + 5, 92, 12, Component.translatable("gui.townsfolk.town_center.name"));
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
		this.relationButton = Button.builder(Component.empty(), b -> this.cycleRelation())
			.bounds(x + 108, y + NAV_Y, 60, 18)
			.build();
		this.addRenderableWidget(this.relationButton);

		this.refreshView();
	}

	/** Updates every widget for the town now on screen. */
	private void refreshView() {
		TownCenterMenu.TownView town = this.current();
		boolean home = this.viewingHome();
		this.scroll = 0;
		// The real banner slot belongs to the opened town; other towns show their banner as a picture.
		this.menu.setShowingHome(home);

		// Only players allowed to rename see the name box; everyone else sees a plain title.
		boolean renaming = this.canRenameHere();
		this.nameBox.setValue(town == null ? "" : town.name());
		this.nameBox.setEditable(renaming);
		this.nameBox.visible = renaming;
		this.nameBox.setFocused(false);
		this.saveButton.visible = renaming;
		this.updateSaveButton();

		int count = this.visibleTowns().size();
		this.prevButton.active = count > 1;
		this.nextButton.active = count > 1;
		// Filled face while filtering to towns with players, hollow face otherwise.
		this.filterButton.setMessage(Component.literal(this.playersOnly ? "☻" : "☺"));

		boolean member = this.isMemberOfHome();
		this.joinButton.setMessage(Component.translatable(home && member ? "gui.townsfolk.town_center.member" : "gui.townsfolk.town_center.join"));
		this.joinButton.active = home && this.menu.canJoin();
		String joinTip = !home ? "gui.townsfolk.town_center.join_elsewhere"
			: member ? "gui.townsfolk.town_center.join_member"
			: this.menu.canJoin() ? "gui.townsfolk.town_center.join_tooltip"
			: "gui.townsfolk.town_center.join_locked";
		String current = this.currentTownElsewhere();
		Component tip = joinTip.equals("gui.townsfolk.town_center.join_tooltip") && current != null
			? Component.translatable("gui.townsfolk.town_center.join_leaves", current)
			: Component.translatable(joinTip);
		this.joinButton.setTooltip(Tooltip.create(tip));

		// Join is only for the opened town; for any other town that spot sets how this town treats it.
		this.joinButton.visible = home || town == null;
		this.relationButton.visible = !home && town != null;
		if (town != null && !home) {
			TownRelations.Relation relation = TownRelations.Relation.byId(town.relation());
			String key = relation.name().toLowerCase(java.util.Locale.ROOT);
			this.relationButton.setMessage(Component.translatable("gui.townsfolk.town_center.relation." + key));
			this.relationButton.active = this.menu.canRename();
			this.relationButton.setTooltip(Tooltip.create(this.menu.canRename()
				? Component.translatable("gui.townsfolk.town_center.relation." + key + ".tooltip", this.menu.home().name(), town.name())
				: Component.translatable("gui.townsfolk.town_center.relation.locked")));
		}
	}

	/** Moves the opened town's stance on the town on screen to the next one: Enemy, Neutral, Good. */
	private void cycleRelation() {
		TownCenterMenu.TownView town = this.current();
		if (town == null || this.viewingHome() || !this.menu.canRename()) {
			return;
		}
		int index = this.menu.towns().indexOf(town);
		TownRelations.Relation next = TownRelations.Relation.byId(town.relation()).next();
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId,
			TownCenterMenu.BUTTON_RELATION_FIRST + index * 3 + next.ordinal());
		this.menu.setRelation(this.minecraft.player, index, next);
		this.refreshView();
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
		String current = this.currentTownElsewhere();
		if (current != null) {
			// Players belong to one town, so moving town is asked about first. Either answer comes back here.
			this.minecraft.gui.setScreen(new ConfirmScreen(
				yes -> {
					this.minecraft.gui.setScreen(this);
					if (yes) {
						this.sendJoin();
					}
				},
				Component.translatable("gui.townsfolk.town_center.move_title"),
				Component.translatable("gui.townsfolk.town_center.move_message", current, this.menu.home().name())
			));
			return;
		}
		this.sendJoin();
	}

	private void sendJoin() {
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TownCenterMenu.BUTTON_JOIN);
		this.menu.markJoined(this.minecraft.player.getName().getString());
		this.refreshView();
	}

	/** The name of another town the viewer is already a player of, or null. */
	private @Nullable String currentTownElsewhere() {
		String me = this.minecraft.player.getName().getString();
		for (TownCenterMenu.TownView town : this.menu.towns()) {
			if (!town.pos().equals(this.menu.pos()) && town.players().contains(me)) {
				return town.name();
			}
		}
		return null;
	}

	/** Renaming is only possible for the town whose Town Center was opened, and only when Trusted. */
	private boolean canRenameHere() {
		return this.viewingHome() && this.menu.canRename();
	}

	private void updateSaveButton() {
		String cleaned = TownNames.clean(this.nameBox.getValue());
		this.saveButton.active = this.canRenameHere() && !cleaned.isEmpty() && !cleaned.equals(this.menu.home().name());
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
		for (Slot slot : this.menu.slots) {
			Panels.slot(graphics, x + slot.x - 1, y + slot.y - 1);
		}

		TownCenterMenu.TownView town = this.current();
		if (town != null) {
			this.extractReputationBar(graphics, town.reputation(), x + LIST_X, y + BAR_Y);
			if (!this.viewingHome() && !town.banner().isEmpty()) {
				graphics.item(town.banner(), x + TownCenterMenu.BANNER_X, y + TownCenterMenu.BANNER_Y);
			}
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
			if (row.section() != null) {
				if (this.rowAt(mouseX, mouseY) == i) {
					// Headings light up under the mouse, to show they can be clicked.
					graphics.fill(x + LIST_X + 1, rowY - 2, x + LIST_X + LIST_W - 6, rowY + 10, HEADER_HOVER_FILL);
				}
				extractArrow(graphics, x + LIST_X + 4, rowY, COLLAPSED.contains(row.section()));
				graphics.text(this.font, row.text(), x + LIST_X + 12, rowY, row.color(), false);
			} else {
				graphics.text(this.font, row.text(), x + LIST_X + (row.header() ? 3 : 8), rowY, row.color(), !row.header());
			}
			if (row.job() != null) {
				// Right-aligned, clear of the remove mark and the scrollbar.
				int right = x + (row.villagerIndex() >= 0 ? REMOVE_X - 4 : LIST_X + LIST_W - 7);
				graphics.text(this.font, row.job(), right - this.font.width(row.job()), rowY, JOB_COLOR, true);
			}
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

		if (!this.canRenameHere()) {
			graphics.text(this.font, town.name(), this.titleLabelX, this.titleLabelY, Panels.TEXT, false);
		}

		int reputation = town.reputation();
		TownReputation.Rank rank = TownReputation.Rank.of(reputation);
		TownReputation.Rank next = rank.next();
		graphics.text(this.font, Component.translatable("gui.townsfolk.town_center.reputation", rank.displayName(), reputation), LIST_X, RANK_Y,
			Panels.TEXT, false);

		Component where = this.viewingHome()
			? Component.translatable("gui.townsfolk.town_center.here")
			: Component.translatable("gui.townsfolk.town_center.distance", (int) Math.round(Math.sqrt(town.pos().distSqr(this.menu.pos()))));
		graphics.text(this.font, where, LIST_X + LIST_W - this.font.width(where), RANK_Y, Panels.TEXT_LIGHT, false);

		Component toNext = next == null
			? Component.translatable("gui.townsfolk.town_center.max_rank")
			: Component.translatable("gui.townsfolk.town_center.next_rank", next.threshold - reputation, next.displayName());
		graphics.text(this.font, toNext, LIST_X, NEXT_RANK_Y, Panels.TEXT_LIGHT, false);
		graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, Panels.TEXT, false);
	}

	/** Below Trusted the opened town's banner can be looked at but not changed, so its slot is shaded. */
	@Override
	public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractContents(graphics, mouseX, mouseY, a);
		if (this.viewingHome() && !this.menu.canRename()) {
			int sx = this.leftPos + TownCenterMenu.BANNER_X;
			int sy = this.topPos + TownCenterMenu.BANNER_Y;
			graphics.fill(sx, sy, sx + 16, sy + 16, 0xB0202020);
		}
	}

	/** Another town's banner, when the mouse is over it. */
	private ItemStack hoveredOtherBanner(int mouseX, int mouseY) {
		TownCenterMenu.TownView town = this.current();
		if (town == null || this.viewingHome()) {
			return ItemStack.EMPTY;
		}
		int lx = mouseX - this.leftPos - TownCenterMenu.BANNER_X;
		int ly = mouseY - this.topPos - TownCenterMenu.BANNER_Y;
		return lx >= 0 && lx < 16 && ly >= 0 && ly < 16 ? town.banner() : ItemStack.EMPTY;
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
		int clicked = this.rowAt(event.x(), event.y());
		if (clicked >= 0 && event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.rows().get(clicked).section() != null) {
			String section = this.rows().get(clicked).section();
			if (!COLLAPSED.remove(section)) {
				COLLAPSED.add(section);
			}
			this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
			this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
			return true;
		}
		int row = this.removeRowAt(event.x(), event.y());
		if (row >= 0 && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
		ItemStack banner = this.hoveredOtherBanner(mouseX, mouseY);
		if (!banner.isEmpty()) {
			graphics.setTooltipForNextFrame(this.font, banner, mouseX, mouseY);
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
