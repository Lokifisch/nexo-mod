package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.wardrobe.NexoSkinLibrary;

/** The last step of adding a skin: a name, Wide or Slim, and a look at it first. */
public class NexoWardrobeDetailsScreen extends NexoModalScreen {
	private static final Identifier PENDING = Identifier.fromNamespaceAndPath("nexomod", "wardrobe/pending");

	private final NexoWardrobeScreen wardrobe;
	private final byte[] png;
	private final Consumer<NexoSkinLibrary.Skin> onAdded;
	private String name;
	private boolean slim;
	private boolean registered;

	public NexoWardrobeDetailsScreen(NexoWardrobeScreen parent, byte[] png, String name, boolean slim, Consumer<NexoSkinLibrary.Skin> onAdded) {
		super(Component.translatable("nexomod.wardrobe.details.title"), parent);
		this.wardrobe = parent;
		this.png = png;
		this.name = name;
		this.slim = slim;
		this.onAdded = onAdded;
	}

	@Override
	protected void init() {
		super.init();
		if (!registered) {
			try {
				NexoSkinLibrary.registerSkin(PENDING, png);
				registered = true;
			} catch (IOException | RuntimeException e) {
				NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not read that skin.", e);
				minecraft.gui.setScreen(parent);
				wardrobe.say(Component.translatable("nexomod.wardrobe.error.badImage"), true);
				return;
			}
		}
		layout.defaultCellSetting().alignHorizontallyCenter();
		EditBox box = layout.addChild(new EditBox(font, 200, 20, title));
		box.setMaxLength(32);
		box.setValue(name);
		box.setResponder(text -> name = text);
		layout.addChild(new Preview());
		LinearLayout model = layout.addChild(LinearLayout.horizontal().spacing(4));
		model.addChild(new NexoWardrobeScreen.Chip(98, 20, Component.translatable("nexomod.wardrobe.wide"), () -> !slim, () -> slim = false));
		model.addChild(new NexoWardrobeScreen.Chip(98, 20, Component.translatable("nexomod.wardrobe.slim"), () -> slim, () -> slim = true));
		LinearLayout buttons = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttons.addChild(NexoButton.builder(CommonComponents.GUI_CANCEL, this::onClose).size(98, 20).build());
		buttons.addChild(new NexoWardrobeScreen.Primary(98, 20, Component.translatable("nexomod.wardrobe.addSkin"), this::add));
		finishLayout();
	}

	private void add() {
		try {
			NexoSkinLibrary.Skin skin = NexoSkinLibrary.add(png, name, slim);
			minecraft.gui.setScreen(parent);
			onAdded.accept(skin);
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not save that skin.", e);
			minecraft.gui.setScreen(parent);
			wardrobe.say(Component.translatable("nexomod.wardrobe.error.save"), true);
		}
	}

	@Override
	public void removed() {
		if (registered) {
			minecraft.getTextureManager().release(PENDING);
			registered = false;
		}
		super.removed();
	}

	private final class Preview extends AbstractWidget {
		Preview() {
			super(0, 0, 200, 130, Component.empty());
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			NexoShapes.outlined(graphics, getX(), getY(), getRight(), getBottom(), 0xFF0A0A0D, NexoStyle.LINE, 4);
			if (registered) {
				NexoWardrobeScreen.drawModel(graphics, PENDING, slim, null, 5F, -25F, getX() + 40, getY() + 4, getRight() - 40, getBottom() - 4);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
		}
	}
}
