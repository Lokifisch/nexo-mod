package dev.nexoclient.nexomod.capes;

import java.util.HashMap;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

/**
 * Draws a cape from {@link NexoCapePhysics}: one simulated horizontal line per
 * texture row, with each strip between two lines textured exactly where vanilla's
 * 10x16x1 cape box puts that row, so every cape texture, HD too, looks as it does
 * stiff. Written from scratch; not derived from any other wavy-cape mod.
 *
 * <p>The physics runs in world space, so it reacts to real momentum. Rendering
 * maps the lines back through the same transform the entity renderer applies
 * (body yaw, the model's flip, its 0.9375 scale).
 */
public final class NexoWavyCape {
	private static final float MODEL_SCALE = 0.9375F;
	/** Blocks from the camera past which players get vanilla's stiff cape instead. */
	private static final double FAR = 32.0;
	/** Simulations by entity id; dropped when not drawn for a while. Render thread only. */
	private static final Map<Integer, Sim> SIMS = new HashMap<>();
	private static long lastPrune;

	private static final class Sim {
		final NexoCapePhysics physics = new NexoCapePhysics(MODEL_SCALE);
		long drawn;
		float yaw;
		float ax, ay, az;
	}

	private NexoWavyCape() {
	}

	/** Draws the cape, or returns false for poses that tilt the whole body, which vanilla's cape handles. */
	public static boolean submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			HumanoidModel<AvatarRenderState> model, Identifier texture, boolean overArmor) {
		// Far away the stiff cape looks the same and costs nothing: a full server stays smooth.
		if (state.distanceToCameraSq > FAR * FAR || state.isVisuallySwimming || state.isAutoSpinAttack || state.isUpsideDown || state.hasPose(Pose.SLEEPING)) {
			SIMS.remove(state.id);
			return false;
		}
		model.setupAnim(state);
		// The cape's pivot in model space, in pixels: vanilla's (0, 0, 2) on the body, which tilts when crouching.
		ModelPart body = model.body;
		float px = body.x;
		float py = body.y - 2F * Mth.sin(body.xRot);
		float pz = body.z + 2F * Mth.cos(body.xRot);
		if (overArmor) {
			py -= 0.053125F * 16F;
			pz += 0.06875F * 16F;
		}

		long now = System.nanoTime();
		prune(now);
		Sim sim = SIMS.computeIfAbsent(state.id, id -> new Sim());
		float seconds = (now - sim.drawn) / 1e9F;
		// A second draw in the same frame (the inventory's figure) shows the world's cape, unchanged.
		boolean sameFrame = sim.physics.placed() && seconds < 0.002F;
		float yaw = sameFrame ? sim.yaw : state.bodyRot;
		float scale = state.scale * MODEL_SCALE;
		Quaternionf toWorld = Axis.YP.rotationDegrees(180F - yaw);
		// Model space to world: the renderer's scale, its (-1, -1, 1) flip, then the body's turn.
		Vector3f anchor = new Vector3f(-px / 16F, -(py / 16F - 1.501F), pz / 16F).mul(scale).rotate(toWorld)
				.add((float) state.x, (float) state.y, (float) state.z);
		Vector3f back = new Vector3f(0F, 0F, 1F).rotate(toWorld);
		if (!sameFrame) {
			// The head as the model just posed it: where its pivot is next to the cape's anchor
			// (it moves when crouching), and how far it is turned and tilted against the body.
			ModelPart head = model.head;
			sim.physics.head(-(head.y - py) / 16F * scale, (head.z - pz) / 16F * scale, head.yRot, head.xRot);
			sim.physics.lively(dev.nexoclient.nexomod.screen.NexoConfig.get().capePhysics() == 2);
			float jump = anchor.distance(sim.ax, sim.ay, sim.az);
			if (!sim.physics.placed() || seconds > 1F || jump > 6F) {
				// New, unseen for a while, or teleported: start hanging.
				sim.physics.reset(anchor.x, anchor.y, anchor.z, back.x, back.z);
			} else {
				sim.physics.advance(seconds, anchor.x, anchor.y, anchor.z, back.x, back.z);
			}
			sim.drawn = now;
			sim.yaw = yaw;
			sim.ax = anchor.x;
			sim.ay = anchor.y;
			sim.az = anchor.z;
		}

		// Each line relative to the top edge, back in the cape's own frame (vanilla's box before its half turn), in pixels.
		Quaternionf toModel = new Quaternionf(toWorld).conjugate();
		int n = NexoCapePhysics.LINES;
		float[] jx = new float[n];
		float[] jy = new float[n];
		float[] jz = new float[n];
		Vector3f v = new Vector3f();
		NexoCapePhysics p = sim.physics;
		for (int i = 0; i < n; i++) {
			v.set(p.x(i) - p.x(0), p.y(i) - p.y(0), p.z(i) - p.z(0)).rotate(toModel).div(scale);
			// World to model flips x and y; the half turn flips x and z: net, y and z flip.
			jx[i] = v.x * 16F;
			jy[i] = -v.y * 16F;
			jz[i] = -v.z * 16F;
		}

		poseStack.pushPose();
		poseStack.translate(px / 16F, py / 16F, pz / 16F);
		poseStack.rotateDegrees(Axis.YP, 180F);
		poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, consumer) -> build(pose, consumer, jx, jy, jz, light));
		poseStack.popPose();
		return true;
	}

	private static void prune(long now) {
		if (now - lastPrune < 10_000_000_000L) {
			return;
		}
		lastPrune = now;
		SIMS.values().removeIf(sim -> now - sim.drawn > 10_000_000_000L);
	}

	/**
	 * Joints are the simulated lines, top edge first, against the back (vanilla's
	 * z = 0 face). The outside sits one pixel along each joint's normal, the cloth's
	 * tangent turned toward the outside (vanilla's z = -1 face).
	 */
	private static void build(PoseStack.Pose pose, VertexConsumer out, float[] jx, float[] jy, float[] jz, int light) {
		int n = jx.length;
		float[] ny = new float[n];
		float[] nz = new float[n];
		for (int j = 0; j < n; j++) {
			int a = Math.max(0, j - 1);
			int b = Math.min(n - 1, j + 1);
			// Tangent x right, with right along +x: (0, tz, -ty).
			float ty = jy[b] - jy[a];
			float tz = jz[b] - jz[a];
			float len = Mth.sqrt(ty * ty + tz * tz);
			ny[j] = len < 1e-5F ? 0F : tz / len;
			nz[j] = len < 1e-5F ? -1F : -ty / len;
		}
		float step = 16F / (n - 1);
		for (int i = 0; i < n - 1; i++) {
			int a = i;
			int b = i + 1;
			float v0 = 1F + i * step;
			float v1 = v0 + step;
			float xa0 = jx[a] - 5F, xa1 = jx[a] + 5F, xb0 = jx[b] - 5F, xb1 = jx[b] + 5F;
			float iay = jy[a], iaz = jz[a], iby = jy[b], ibz = jz[b];
			float oay = iay + ny[a], oaz = iaz + nz[a], oby = iby + ny[b], obz = ibz + nz[b];
			float sny = (ny[a] + ny[b]) / 2F, snz = (nz[a] + nz[b]) / 2F;

			// Outside: vanilla's north face, u 1..11.
			quad(pose, out, light, 0F, sny, snz,
					xa1, oay, oaz, 11F, v0, xa0, oay, oaz, 1F, v0, xb0, oby, obz, 1F, v1, xb1, oby, obz, 11F, v1);
			// Against the back: the south face, u 12..22, mirrored.
			quad(pose, out, light, 0F, -sny, -snz,
					xa0, iay, iaz, 22F, v0, xa1, iay, iaz, 12F, v0, xb1, iby, ibz, 12F, v1, xb0, iby, ibz, 22F, v1);
			// Edges: west u 0..1, east u 11..12.
			quad(pose, out, light, -1F, 0F, 0F,
					xa0, oay, oaz, 1F, v0, xa0, iay, iaz, 0F, v0, xb0, iby, ibz, 0F, v1, xb0, oby, obz, 1F, v1);
			quad(pose, out, light, 1F, 0F, 0F,
					xa1, iay, iaz, 12F, v0, xa1, oay, oaz, 11F, v0, xb1, oby, obz, 11F, v1, xb1, iby, ibz, 12F, v1);
			float ty = jy[b] - jy[a], tz = jz[b] - jz[a];
			float tl = Math.max(1e-5F, Mth.sqrt(ty * ty + tz * tz));
			if (i == 0) {
				// Top edge: u 1..11, v 0..1, facing back up the cloth.
				quad(pose, out, light, 0F, -ty / tl, -tz / tl,
						xa1, iay, iaz, 11F, 0F, xa0, iay, iaz, 1F, 0F, xa0, oay, oaz, 1F, 1F, xa1, oay, oaz, 11F, 1F);
			}
			if (i == n - 2) {
				// Hem: u 11..21, v 1 (outside) to 0 (inside).
				quad(pose, out, light, 0F, ty / tl, tz / tl,
						xb1, oby, obz, 21F, 1F, xb0, oby, obz, 11F, 1F, xb0, iby, ibz, 11F, 0F, xb1, iby, ibz, 21F, 0F);
			}
		}
	}

	@SuppressWarnings("java:S107")
	private static void quad(PoseStack.Pose pose, VertexConsumer out, int light, float nx, float ny, float nz,
			float ax, float ay, float az, float au, float av, float bx, float by, float bz, float bu, float bv,
			float cx, float cy, float cz, float cu, float cv, float dx, float dy, float dz, float du, float dv) {
		vertex(pose, out, light, nx, ny, nz, ax, ay, az, au, av);
		vertex(pose, out, light, nx, ny, nz, bx, by, bz, bu, bv);
		vertex(pose, out, light, nx, ny, nz, cx, cy, cz, cu, cv);
		vertex(pose, out, light, nx, ny, nz, dx, dy, dz, du, dv);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, int light, float nx, float ny, float nz, float x, float y, float z, float u, float v) {
		out.addVertex(pose, x, y, z).setColor(-1).setUv(u / 64F, v / 32F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
	}
}
