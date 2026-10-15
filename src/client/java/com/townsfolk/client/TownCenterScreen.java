package com.townsfolk.client;

import com.townsfolk.menu.TownCenterMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/** Shows the town's population and a scrollable list of resident names. */
public class TownCenterScreen extends AbstractContainerScreen<TownCenterMenu> {
	private static final int ROW_HEIGHT = 12;
	private static final int LIST_X = 8;
	private static final int LIST_Y = 32;
	private static final int LIST_W = 160;
	private static final int LIST_H = 124;

	private int scroll;

	public TownCenterScreen(TownCenterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 166);
	}

	private int maxScroll() {
		int visibleRows = (LIST_H - 4) / ROW_HEIGHT;
		return Math.max(0, this.menu.residents().size() - visibleRows);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		Panels.window(graphics, x, y, this.imageWidth, this.imageHeight);
		Panels.inset(graphics, x + LIST_X, y + LIST_Y, LIST_W, LIST_H, 0xFF8B8B8B);

		List<String> residents = this.menu.residents();
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
			graphics.text(this.font, (i + 1) + ". " + residents.get(i), x + LIST_X + 4, rowY, 0xFFFFFFFF, true);
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

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		graphics.text(this.font, this.title, this.titleLabelX, this.titleLabelY, Panels.TEXT, false);
		Component population = Component.translatable("gui.townsfolk.town_center.population", this.menu.residents().size());
		graphics.text(this.font, population, LIST_X, 20, Panels.TEXT, false);
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
