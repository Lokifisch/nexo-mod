package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * An {@link EditBox} with click-drag select, double/triple-click word/all
 * select, undo/redo, and validator-driven error coloring. Ported from
 * CommandKeys' {@code TextField} (see THIRD-PARTY-NOTICES.md) since its
 * behavior is otherwise identical here; only the translation key for the
 * built-in positive-integer validator was renamed to this mod's own.
 */
public class NexoTextField extends EditBox {
	private static final long CLICK_CHAIN_TIME = 250L;
	public static final int TEXT_COLOR_DEFAULT = 0xFFE0E0E0;
	public static final int TEXT_COLOR_ERROR = 0xFFFF5555;

	private final Font font;
	private final List<Validator> validators = new ArrayList<>();
	private int normalTextColor = TEXT_COLOR_DEFAULT;
	private Tooltip normalTooltip;
	private Tooltip errorTooltip;

	private final List<String> history = new ArrayList<>();
	private int historyIndex = -1;

	private double dragOriginX;
	private int dragOriginPos;
	private long lastClickTime;
	private int chainedClicks;

	public NexoTextField(int x, int y, int width, int height) {
		this(Minecraft.getInstance().font, x, y, width, height);
	}

	public NexoTextField(Font font, int x, int y, int width, int height) {
		super(font, x, y, width, height, Component.empty());
		this.font = font;
	}

	public NexoTextField withValidator(Validator validator) {
		validators.add(validator);
		return this;
	}

	public NexoTextField posIntValidator() {
		validators.add(str -> {
			try {
				if (Integer.parseInt(str.trim()) < 0) {
					throw new NumberFormatException();
				}
				return Optional.empty();
			} catch (NumberFormatException e) {
				return Optional.of(Component.translatable("nexomod.macros.field.posInt"));
			}
		});
		return this;
	}

	@Override
	public void setResponder(Consumer<String> responder) {
		super.setResponder(str -> {
			updateHistory(str);
			validate(str);
			responder.accept(str);
		});
	}

	private void validate(String str) {
		for (Validator v : validators) {
			Optional<Component> error = v.validate(str);
			if (error.isPresent()) {
				errorTooltip = Tooltip.create(error.get());
				super.setTooltip(errorTooltip);
				super.setTextColor(TEXT_COLOR_ERROR);
				return;
			}
		}
		errorTooltip = null;
		super.setTextColor(normalTextColor);
		super.setTooltip(normalTooltip);
	}

	@Override
	public void setTooltip(Tooltip tooltip) {
		normalTooltip = tooltip;
		if (errorTooltip == null) {
			super.setTooltip(tooltip);
		}
	}

	@Override
	public void setTextColor(int color) {
		normalTextColor = color;
		if (errorTooltip == null) {
			super.setTextColor(color);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			long time = Util.getMillis();
			if (lastClickTime + CLICK_CHAIN_TIME > time) {
				switch (++chainedClicks) {
					case 1 -> {
						int pos = getCursorPosition();
						int start = pos;
						if (pos < 0) {
							start = 0;
						} else if (pos >= getValue().length() || getValue().charAt(pos) == ' '
								|| (pos > 0 && getValue().charAt(pos - 1) != ' ')) {
							start = getWordPosition(-1);
						}
						int end = getWordPosition(1);
						moveCursorTo(start, false);
						moveCursorTo(end, true);
					}
					case 2, 3 -> {
						moveCursorToEnd(false);
						setHighlightPos(0);
					}
					default -> {
						chainedClicks = 0;
						setHighlightPos(getCursorPosition());
					}
				}
			} else {
				chainedClicks = 0;
			}
			lastClickTime = time;
			dragOriginX = event.x();
			dragOriginPos = getCursorPosition();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (event.button() != 0) {
			return false;
		}
		String str = getValue();
		if (event.x() < dragOriginX) {
			String subLeft = str.substring(0, dragOriginPos);
			int offsetChars = font.plainSubstrByWidth(subLeft, Mth.floor(dragOriginX - event.x()), true).length();
			moveCursorTo(dragOriginPos - offsetChars, true);
		} else {
			String subRight = str.substring(dragOriginPos);
			int offsetChars = font.plainSubstrByWidth(subRight, Mth.floor(event.x() - dragOriginX), false).length();
			moveCursorTo(dragOriginPos + offsetChars, true);
		}
		return true;
	}

	private void updateHistory(String str) {
		if (historyIndex == -1 || !history.get(historyIndex).equals(str)) {
			if (historyIndex < history.size() - 1) {
				for (int i = history.size() - 1; i > historyIndex; i--) {
					history.remove(history.size() - 1);
				}
			}
			history.add(str);
			historyIndex++;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!super.keyPressed(event)) {
			if (isUndo(event)) {
				undo();
				return true;
			} else if (isRedo(event)) {
				redo();
				return true;
			}
			return false;
		}
		return true;
	}

	private void undo() {
		if (historyIndex > 0) {
			setValue(history.get(--historyIndex));
		}
	}

	private void redo() {
		if (historyIndex < history.size() - 1) {
			setValue(history.get(++historyIndex));
		}
	}

	private static boolean isUndo(KeyEvent event) {
		return event.key() == InputConstants.KEY_Z && event.hasControlDown() && !event.hasShiftDown() && !event.hasAltDown();
	}

	private static boolean isRedo(KeyEvent event) {
		return event.key() == InputConstants.KEY_Y && event.hasControlDown() && !event.hasShiftDown() && !event.hasAltDown();
	}

	@FunctionalInterface
	public interface Validator {
		Optional<Component> validate(String str);
	}
}
