package com.townsfolk.client;

import com.townsfolk.menu.TownCenterMenu;
import com.townsfolk.town.RenameTownPayload;
import com.townsfolk.town.TownNames;
import com.townsfolk.town.TownReputation;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/** Shows the town's population, the viewer's reputation and a scrollable list of resident names. */
public class TownCenterScreen extends AbstractContainerScreen<TownCenterMenu> {
	private static final int ROW_HEIGHT = 12;
	private static final int LIST_X = 8;
	private static final int LIST_Y = 58;
	private static final int LIST_W = 160;
	private static final int LIST_H = 98;
	private static final int BAR_Y = 42;

	private int scroll;
	private EditBox nameBox;
	private Button saveButton;

	public TownCenterScreen(TownCenterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 166);
	}

	/** Villagers first, then golems; golem rows are drawn in a steel colour. */
	private List<String> rows() {
		List<String> rows = new ArrayList<>(this.menu.residents());
		rows.addAll(this.menu.golems());
		return rows;
	}

	@Override
	protected void init() {
		super.init();
		this.nameBox = new EditBox(this.font, this.leftPos + 8, this.topPos + 5, 112, 12, Component.translatable("gui.townsfolk.town_center.name"));
		this.nameBox.setMaxLength(TownNames.MAX_LENGTH);
		this.nameBox.setValue(this.menu.townName());
		this.nameBox.setEditable(this.menu.canRename());
		this.nameBox.setResponder(value -> this.updateSaveButton());
		this.addRenderableWidget(this.nameBox);

		this.saveButton = Button.builder(Component.translatable("gui.townsfolk.town_center.rename"), b -> this.saveName())
			.bounds(this.leftPos + 124, this.topPos + 4, 44, 14)
			.build();
		this.addRenderableWidget(this.saveButton);
		this.updateSaveButton();
	}

	private void updateSaveButton() {
		String cleaned = TownNames.clean(this.nameBox.getValue());
		this.saveButton.active = this.menu.canRename() && !cleaned.isEmpty() && !cleaned.equals(this.menu.townName());
		this.saveButton.setTooltip(this.menu.canRename() ? null : Tooltip.create(Component.translatable("gui.townsfolk.town_center.rename_locked")));
	}

	private void saveName() {
		String cleaned = TownNames.clean(this.nameBox.getValue());
		if (!this.menu.canRename() || cleaned.isEmpty()) {
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
		this.extractReputationBar(graphics, x + LIST_X, y + BAR_Y);

		List<String> residents = this.rows();
		int villagerCount = this.menu.residents().size();
		graphics.enableScissor(x + LIST_X + 1, y + LIST_Y + 1, x + LIST_X + LIST_W - 1, y + LIST_Y + LIST_H - 1);
		if (residents.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("gui.townsfolk.town_center.empty"),
				x + LIST_X + LIST_W / 2, y + LIST_Y + LIST_H / 2 - 4, 0xFFE0E0E0);
		}
		for (int i = this.scroll; i < residents.size(); i++) {
			int rowY = y + LIST_Y + 3 + (i - this.scroll) * ROW_HEIGHT;
			if (rowY > y + LIST_Y + LIST_H) {
				break;
			}
			boolean golem = i >= villagerCount;
			String label = golem ? "\u2699 " + residents.get(i) : (i + 1) + ". " + residents.get(i);
			graphics.text(this.font, label, x + LIST_X + 4, rowY, golem ? 0xFFB8C8D8 : 0xFFFFFFFF, true);
		}
		graphics.disableScissor();

		if (this.maxScroll() > 0) {
			int trackH = LIST_H - 2;
			int thumbH = Math.max(10, trackH * (residents.size() - this.maxScroll()) / residents.size());
			int thumbY = y + LIST_Y + 1 + (trackH - thumbH) * this.scroll / this.maxScroll();
			int barX = x + LIST_X + LIST_W - 5;
			graphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFC6C6C6);
		}
	}

	/** Progress towards the next rank; full at the top rank. */
	private void extractReputationBar(GuiGraphicsExtractor graphics, int x, int y) {
		int reputation = this.menu.reputation();
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
		Component population = Component.translatable("gui.townsfolk.town_center.population", this.menu.residents().size(), this.menu.golems().size());
		graphics.text(this.font, population, LIST_X, 20, Panels.TEXT, false);

		int reputation = this.menu.reputation();
		TownReputation.Rank rank = TownReputation.Rank.of(reputation);
		TownReputation.Rank next = rank.next();
		graphics.text(this.font, Component.translatable("gui.townsfolk.town_center.reputation", rank.displayName(), reputation), LIST_X, 31,
			rank == TownReputation.Rank.ENEMY ? 0xFFA02020 : Panels.TEXT, false);
		Component toNext = next == null
			? Component.translatable("gui.townsfolk.town_center.max_rank")
			: Component.translatable("gui.townsfolk.town_center.next_rank", next.threshold - reputation, next.displayName());
		int width = this.font.width(toNext);
		graphics.text(this.font, toNext, LIST_X + LIST_W - width, 20, 0xFF606060, false);
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
