package dev.nexoclient.nexomod.screen;

import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.cosmetics.CosmeticsCatalog;
import dev.nexoclient.nexomod.cosmetics.CosmeticsIdentity;
import dev.nexoclient.nexomod.cosmetics.NexoCosmetics;
import dev.nexoclient.nexomod.net.MojangIdentityProof;

/**
 * Row-per-cosmetic list for {@link NexoCosmeticsScreen}. Same shape as
 * {@code NexoQuickServerOptionList}: rows are built directly from
 * {@link NexoOptionList.Entry} rather than the plain {@code Text}/
 * {@code ActionButton} helpers, since each row needs a label and a button
 * together.
 */
public class NexoCosmeticsOptionList extends NexoOptionList {
	private static final String CAPE_SLOT = "cape";

	private NexoCosmeticsScreen owningScreen;

	public NexoCosmeticsOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight,
			int entrySpacing, NexoCosmeticsScreen owningScreen) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
		this.owningScreen = owningScreen;
	}

	void setOwningScreen(NexoCosmeticsScreen owningScreen) {
		this.owningScreen = owningScreen;
	}

	private static UUID localPlayerId() {
		var user = MojangIdentityProof.currentUser();
		return user == null ? null : user.getProfileId();
	}

	@Override
	protected void addEntries() {
		Integer balance = owningScreen.balance();
		Component balanceText = balance == null
				? Component.translatable("nexomod.cosmetics.balance.loading")
				: Component.translatable("nexomod.cosmetics.balance", balance);
		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight, balanceText, null, -1));

		UUID localId = localPlayerId();
		Integer equippedCape = localId == null ? null : NexoCosmetics.equipped().equippedCosmetic(localId, CAPE_SLOT);

		addEntry(new OfficialCapeRow(dynEntryX, dynEntryWidth, entryHeight, this, equippedCape == null));

		List<CosmeticsCatalog.Item> items = NexoCosmetics.catalog().items();
		boolean anyCapes = false;
		for (CosmeticsCatalog.Item item : items) {
			if (!"cape".equals(item.type())) {
				continue;
			}
			anyCapes = true;
			boolean equipped = equippedCape != null && equippedCape == item.id();
			addEntry(new CosmeticRow(dynEntryX, dynEntryWidth, entryHeight, this, item, equipped));
		}
		if (!anyCapes) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.cosmetics.none"), null, -1));
		}
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean keyReleased(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean mouseClicked(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean mouseReleased(InputConstants.Key key) {
		return false;
	}

	/** Falling back to whatever the account's own Mojang profile provides — see {@code VanillaCapeSuppressionMixin}. */
	private static class OfficialCapeRow extends NexoOptionList.Entry {
		OfficialCapeRow(int x, int width, int height, NexoCosmeticsOptionList list, boolean active) {
			int buttonWidth = 90;
			int labelWidth = width - buttonWidth - SPACE;

			elements.add(new StringWidget(x, 0, labelWidth, height,
					Component.translatable("nexomod.cosmetics.official"), Minecraft.getInstance().font));

			Component buttonLabel = Component.translatable(
					active ? "nexomod.cosmetics.active" : "nexomod.cosmetics.use");
			Button button = Button.builder(buttonLabel, b -> NexoCosmetics.unequip(CAPE_SLOT, ok -> {
						if (ok) {
							list.init();
						}
					}))
					.pos(x + labelWidth + SPACE, 0).size(buttonWidth, height).build();
			button.active = !active;
			elements.add(button);
		}
	}

	private static class CosmeticRow extends NexoOptionList.Entry {
		CosmeticRow(int x, int width, int height, NexoCosmeticsOptionList list, CosmeticsCatalog.Item item, boolean equipped) {
			int buttonWidth = 90;
			int labelWidth = width - buttonWidth - SPACE;

			Component priceText = item.price() == 0
					? Component.translatable("nexomod.cosmetics.free")
					: Component.translatable("nexomod.cosmetics.price", item.price());
			Component label = Component.literal(item.name() + " — ").append(priceText);
			elements.add(new StringWidget(x, 0, labelWidth, height, label, Minecraft.getInstance().font));

			boolean owns = item.price() == 0 || list.owningScreen.owns(item.id());

			Component buttonLabel;
			Button.OnPress action;
			boolean active = true;
			if (equipped) {
				buttonLabel = Component.translatable("nexomod.cosmetics.active");
				action = b -> {};
				active = false;
			} else if (!owns) {
				buttonLabel = Component.translatable("nexomod.cosmetics.buy", item.price());
				action = b -> NexoCosmetics.purchase(item.id(), result -> {
					if (result == CosmeticsIdentity.PurchaseResult.PURCHASED
							|| result == CosmeticsIdentity.PurchaseResult.ALREADY_OWNED) {
						list.owningScreen.markOwned(item.id());
						list.owningScreen.refreshWallet();
						list.init();
					}
				});
			} else {
				buttonLabel = Component.translatable("nexomod.cosmetics.use");
				action = b -> NexoCosmetics.equip(CAPE_SLOT, item.id(), ok -> {
					if (ok) {
						list.init();
					}
				});
			}
			Button button = Button.builder(buttonLabel, action).pos(x + labelWidth + SPACE, 0).size(buttonWidth, height).build();
			button.active = active;
			elements.add(button);
		}
	}
}
