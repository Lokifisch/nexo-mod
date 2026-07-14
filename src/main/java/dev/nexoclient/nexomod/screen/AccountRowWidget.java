package dev.nexoclient.nexomod.screen;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

/** One row in the account switcher: face icon + name, click to switch to this account. */
public class AccountRowWidget extends AbstractButton {
	private static final int ICON_SIZE = 16;

	private final boolean isCurrent;
	private final boolean isLauncherAccount;
	private final boolean isOffline;
	private final Runnable onSelect;
	private volatile PlayerSkin skin;

	public AccountRowWidget(int x, int y, int width, int height, UUID uuid, String name, boolean isCurrent,
			boolean isLauncherAccount, boolean isOffline, Runnable onSelect) {
		super(x, y, width, height, Component.literal(name));
		this.isCurrent = isCurrent;
		this.isLauncherAccount = isLauncherAccount;
		this.isOffline = isOffline;
		this.onSelect = onSelect;

		if (isOffline) {
			this.skin = DefaultPlayerSkin.get(uuid);
			return;
		}

		// A bare GameProfile(uuid, name) has no "textures" property, and SkinManager
		// only reads a property that's already on the profile — it doesn't resolve
		// one itself. Fetching the real profile first is what actually gets the
		// account's skin instead of silently falling back to default Steve/Alex.
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> mc.services().sessionService().fetchProfile(uuid, false), Util.nonCriticalIoPool())
				.thenCompose(profileResult -> mc.getSkinManager().get(profileResult.profile()))
				.thenAccept(result -> result.ifPresent(s -> this.skin = s));
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onSelect.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = getX();
		int y0 = getY();
		int x1 = getX() + getWidth();
		int y1 = getY() + getHeight();

		if (isCurrent) {
			graphics.fill(x0, y0, x1, y1, NexoStyle.PANEL_BG_RAISED);
			graphics.fill(x0, y0, x0 + 2, y1, NexoStyle.TEXT_ACTIVE_ACCENT);
		} else if (isHoveredOrFocused()) {
			graphics.fill(x0, y0, x1, y1, NexoStyle.PANEL_BG_RAISED);
			graphics.fill(x0, y0, x0 + 2, y1, NexoStyle.BORDER_DIM);
		}

		int iconX = getX() + 4;
		int iconY = getY() + (getHeight() - ICON_SIZE) / 2;
		if (skin != null) {
			PlayerFaceExtractor.extractRenderState(graphics, skin, iconX, iconY, ICON_SIZE);
		}

		boolean hasBottomTag = isLauncherAccount || isOffline;
		Component nameLabel = getMessage()
				.copy()
				.withStyle(style -> style.withColor(isCurrent ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.TEXT_PRIMARY));
		int nameY = hasBottomTag ? getY() + 2 : getY() + (getHeight() - 8) / 2;
		graphics.text(Minecraft.getInstance().font, nameLabel, iconX + ICON_SIZE + 6, nameY, -1);

		if (hasBottomTag) {
			Component tag = Component.translatable(isLauncherAccount ? "nexomod.accounts.launcherTag" : "nexomod.accounts.offlineTag")
					.withStyle(style -> style.withColor(NexoStyle.TEXT_SECONDARY));
			graphics.text(Minecraft.getInstance().font, tag, iconX + ICON_SIZE + 6, getY() + getHeight() - 10, -1);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
