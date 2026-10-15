package com.townsfolk.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Draws vanilla-styled GUI panels from plain fills, so the mod ships without custom GUI textures. */
final class Panels {
	static final int TEXT = 0xFF404040;
	static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	private static final int BORDER = 0xFF000000;
	private static final int FACE = 0xFFC6C6C6;
	private static final int LIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;

	private Panels() {
	}

	/** Raised grey window like vanilla container backgrounds. */
	static void window(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x + 1, y, x + w - 1, y + h, BORDER);
		graphics.fill(x, y + 1, x + w, y + h - 1, BORDER);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, FACE);
		graphics.fill(x + 1, y + 1, x + w - 2, y + 3, LIGHT);
		graphics.fill(x + 1, y + 1, x + 3, y + h - 2, LIGHT);
		graphics.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, SHADOW);
		graphics.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, SHADOW);
		graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, FACE);
	}

	/** Sunken box, used behind the entity preview and the resident list. */
	static void inset(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill) {
		graphics.fill(x, y, x + w, y + h, SHADOW);
		graphics.fill(x + 1, y + 1, x + w, y + h, LIGHT);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
	}

	static void slot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, 18, 18);
	}
}
