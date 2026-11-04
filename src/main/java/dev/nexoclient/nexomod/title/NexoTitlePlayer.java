package dev.nexoclient.nexomod.title;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The title screen's player, moving like the launcher's Home figure
 * ({@code Client/.../skin3d.rs}, {@code Stance::Idle}): breathing, the arms
 * drifting out and back, the head following the cursor for a few seconds and
 * glancing round for a few more. It can be dragged round, holds the dragged
 * pose for five seconds, then eases back to rest. Same constants as the
 * launcher; angles here are radians unless named in degrees.
 *
 * <p>Replaces vanilla's {@code PlayerSkinWidget}, which only turns when dragged.
 * Lives in {@code title/} so the title layout leaves its position alone.
 */
final class NexoTitlePlayer extends AbstractWidget {
	/** Turned to its right and tipped forward, as the launcher rests it. */
	private static final float REST_YAW = -0.62F;
	private static final float REST_PITCH = 0.20F;
	private static final long HOLD_MS = 5_000;
	private static final float RETURN_RATE = 2.2F;
	private static final float LOOK_RATE = 5.0F;
	private static final float MAX_LOOK_YAW = 1.0F;
	private static final float MAX_LOOK_PITCH = 0.55F;
	private static final float MAX_PITCH = 0.9F;
	/** Radians per physical pixel, as in the launcher. */
	private static final float DRAG_SENSITIVITY = 0.011F;

	private final Supplier<PlayerSkin> skin;
	private Model.Simple wide;
	private Model.Simple slim;

	private float yaw = REST_YAW;
	private float pitch = REST_PITCH;
	/** The head's turn from the body, toward the cursor or wandering. */
	private float lookYaw;
	private float lookPitch;
	private boolean dragging;
	private long releasedAt = -1;
	private boolean attending;
	private long switchAt = -1;
	private int switches;
	private final long started = Util.getMillis();
	private long lastFrame = -1;

	NexoTitlePlayer(int width, int height, Supplier<PlayerSkin> skin) {
		super(0, 0, width, height, Component.empty());
		this.skin = skin;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		PlayerSkin current = skin.get();
		if (current == null || getWidth() < 2 || getHeight() < 2) {
			return;
		}
		long now = Util.getMillis();
		float dt = lastFrame < 0 ? 1F / 60F : Math.min((now - lastFrame) / 1000F, 0.1F);
		lastFrame = now;
		float t = (now - started) / 1000F;
		update(now, t, dt, mouseX, mouseY);

		Model.Simple model = model(current.model() == PlayerModelType.SLIM);
		ModelPart root = model.root();
		// Rig::idle: swings positive forwards, spreads positive away from the body.
		float swing = Mth.sin(t * 1.34F) * 0.16F;
		float spread = 0.09F + Mth.cos(t * 1.8F) * 0.08F;
		float bob = Mth.sin(t * 1.8F) * 0.9F;
		ModelPart head = root.getChild("head");
		// Minecraft turns a head towards the model's own right for a positive yRot,
		// which is the viewer's left on a figure facing out; look is in screen terms.
		head.yRot = -lookYaw;
		head.xRot = lookPitch;
		head.zRot = 0F;
		ModelPart body = root.getChild("body");
		body.xRot = body.yRot = body.zRot = 0F;
		ModelPart right = root.getChild("right_arm");
		right.xRot = -swing;
		right.yRot = 0F;
		right.zRot = spread;
		ModelPart left = root.getChild("left_arm");
		left.xRot = swing;
		left.yRot = 0F;
		left.zRot = -spread;
		for (String leg : new String[] {"right_leg", "left_leg"}) {
			ModelPart part = root.getChild(leg);
			part.xRot = part.yRot = part.zRot = 0F;
		}

		// The bob moves the whole model in model units: a float translation inside the
		// offscreen render, where moving the picture's bounds would step a whole GUI
		// pixel at a time and stutter. Positive is down there, so rising is negative.
		root.y = -bob;
		// 0.92 of the height rather than vanilla's 0.97: headroom for the bob's peak
		// and a raised glance, which would otherwise clip the top of the hat.
		graphics.skin(model, current.body().texturePath(), 0.92F * getHeight() / 2.125F,
				// Minecraft tips the top towards the viewer for a negative rotationX; the launcher's pitch is the other way.
				-pitch * Mth.RAD_TO_DEG, yaw * Mth.RAD_TO_DEG, -1.0625F, getX(), getY(), getRight(), getBottom());
	}

	private void update(long now, float t, float dt, int cursorX, int cursorY) {
		// Looks at the cursor a while, then away a while, and back.
		if (switchAt < 0) {
			switchAt = now + 1_500;
		}
		if (now >= switchAt) {
			attending = !attending;
			switches++;
			float span = attending ? vary(switches, 3F, 5F) : vary(switches, 2.5F, 6F);
			switchAt = now + (long) (span * 1000F);
		}
		float targetYaw, targetPitch;
		if (attending) {
			// ponytail: the head's centre is a fixed point of the widget, not projected
			// through the pose as the launcher does; close enough at these angles.
			// The head's centre, near the top of the figure; treated as a little in
			// front of the screen, so a cursor right beside it turns it fully.
			float headX = getX() + getWidth() / 2F;
			float headY = getY() + getHeight() * 0.16F;
			float depth = Math.max(1, getHeight()) * 0.9F;
			float towardYaw = (float) Math.atan((cursorX - headX) / depth);
			float towardPitch = (float) Math.atan((cursorY - headY) / depth);
			// The body faces the viewer's right for a positive yaw, the same sense.
			targetYaw = Mth.clamp(Mth.wrapDegrees((towardYaw - yaw) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD, -MAX_LOOK_YAW, MAX_LOOK_YAW);
			targetPitch = Mth.clamp(towardPitch - pitch, -MAX_LOOK_PITCH, MAX_LOOK_PITCH);
		} else {
			// A slow glance round, never quite still.
			targetYaw = Mth.sin(t * 0.45F) * 0.35F + Mth.sin(t * 0.17F) * 0.15F;
			targetPitch = Mth.sin(t * 0.31F) * 0.12F - 0.05F;
		}
		float look = 1F - (float) Math.exp(-LOOK_RATE * dt);
		lookYaw += (targetYaw - lookYaw) * look;
		lookPitch += (targetPitch - lookPitch) * look;

		// Hold a dragged pose, then ease back to rest.
		if (dragging || (releasedAt >= 0 && now - releasedAt < HOLD_MS)) {
			return;
		}
		float back = 1F - (float) Math.exp(-RETURN_RATE * dt);
		yaw += (REST_YAW - yaw) * back;
		pitch += (REST_PITCH - pitch) * back;
	}

	/** A number in {@code from..to} from {@code n}, the same every time: keeps the timing off a metronome. */
	private static float vary(int n, float from, float to) {
		double noise = Math.abs((Math.sin(n * 12.9898) * 43_758.547) % 1.0);
		return from + (to - from) * (float) noise;
	}

	private Model.Simple model(boolean slimArms) {
		if (wide == null) {
			var models = Minecraft.getInstance().getEntityModels();
			wide = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
			slim = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
		}
		return slimArms ? slim : wide;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		dragging = true;
		releasedAt = -1;
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		float perGuiPixel = DRAG_SENSITIVITY * (float) Minecraft.getInstance().getWindow().getGuiScale();
		yaw += (float) dragX * perGuiPixel;
		pitch = Mth.clamp(pitch + (float) dragY * perGuiPixel, -MAX_PITCH, MAX_PITCH);
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		dragging = false;
		// Spun round several times, it would unwind every turn on its way back.
		yaw = REST_YAW + Mth.wrapDegrees((yaw - REST_YAW) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
		releasedAt = Util.getMillis();
	}

	@Override
	public void playDownSound(SoundManager sounds) {
	}

	/** Not a Tab stop, as vanilla's player widget isn't: there is nothing to do with it by keyboard. */
	@Override
	public ComponentPath nextFocusPath(FocusNavigationEvent event) {
		return null;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}
}
