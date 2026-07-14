package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.auth.AccountStore;
import dev.nexoclient.nexomod.auth.LauncherAccount;
import dev.nexoclient.nexomod.auth.LoginFlow;
import dev.nexoclient.nexomod.auth.MinecraftAccount;
import dev.nexoclient.nexomod.auth.SessionSwap;

/** Popup for picking, adding, or removing signed-in Minecraft accounts. */
public class AccountSwitcherScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 240;
	private static final int ROW_HEIGHT = 24;

	public AccountSwitcherScreen(Screen parent) {
		super(Component.translatable("nexomod.accounts.title"), parent);
	}

	@Override
	protected void init() {
		super.init();
		LauncherAccount.captureIfNeeded();

		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		AccountStore store = AccountStore.get();
		UUID activeUuid = store.active().map(MinecraftAccount::uuid).orElseGet(() -> LauncherAccount.user().map(u -> u.getProfileId()).orElse(null));

		for (RowEntry entry : buildRows(store)) {
			LinearLayout row = layout.addChild(LinearLayout.horizontal().spacing(4));
			boolean isCurrent = activeUuid != null && activeUuid.equals(entry.uuid);
			boolean isOffline = entry.storedAccount() != null && entry.storedAccount().offline();
			row.addChild(new AccountRowWidget(0, 0, ROW_WIDTH, ROW_HEIGHT, entry.uuid, entry.name, isCurrent, entry.isLauncherAccount, isOffline,
					() -> switchTo(entry)));

			NexoButton logoutButton = NexoButton.builder(Component.literal("..."), () -> confirmLogout(entry)).size(20, ROW_HEIGHT).build();
			logoutButton.active = !entry.isLauncherAccount;
			row.addChild(logoutButton);
		}

		LinearLayout buttonRow = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(10);
		buttonRow.addChild(NexoButton.builder(Component.translatable("nexomod.accounts.add"), () -> LoginFlow.start(this)).build());
		buttonRow.addChild(NexoButton.builder(Component.translatable("nexomod.accounts.offline"), () -> minecraft.setScreen(new OfflineLoginScreen(this))).build());
		buttonRow.addChild(NexoButton.builder(CommonComponents.GUI_DONE, this::onClose).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private record RowEntry(UUID uuid, String name, boolean isLauncherAccount, MinecraftAccount storedAccount) {}

	/** Every stored account, plus (if it isn't already one of them) a synthetic row for whatever account actually launched the game. */
	private static List<RowEntry> buildRows(AccountStore store) {
		Map<UUID, RowEntry> rows = new LinkedHashMap<>();
		for (MinecraftAccount account : store.accounts()) {
			rows.put(account.uuid(), new RowEntry(account.uuid(), account.name(), LauncherAccount.is(account.uuid()), account));
		}
		LauncherAccount.user().ifPresent(user -> rows.putIfAbsent(
				user.getProfileId(), new RowEntry(user.getProfileId(), user.getName(), true, null)));
		return new ArrayList<>(rows.values());
	}

	private void switchTo(RowEntry entry) {
		if (entry.storedAccount() == null) {
			// Not a stored (mod-managed) account — must be the launcher's own, un-refreshable session.
			SessionSwap.restoreLauncherAccount();
			minecraft.setScreen(new AccountSwitcherScreen(parent));
			return;
		}
		LoginFlow.switchTo(entry.storedAccount(),
				() -> minecraft.setScreen(new AccountSwitcherScreen(parent)),
				error -> minecraft.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
						() -> minecraft.setScreen(new AccountSwitcherScreen(parent)),
						Component.translatable("nexomod.login.failedTitle"),
						Component.literal(error.getMessage() != null ? error.getMessage() : error.toString()))));
	}

	private void confirmLogout(RowEntry entry) {
		if (entry.isLauncherAccount()) {
			return;
		}
		minecraft.setScreen(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						AccountStore.get().remove(entry.uuid());
					}
					minecraft.setScreen(new AccountSwitcherScreen(parent));
				},
				Component.translatable("nexomod.accounts.logoutTitle"),
				Component.translatable("nexomod.accounts.logoutConfirm", entry.name())));
	}
}
