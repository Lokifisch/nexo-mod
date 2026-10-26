package dev.nexoclient.nexomod.screen;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;

/**
 * Vanilla's opaque menu-background pipeline, except that it always passes the
 * depth test.
 *
 * <p>Why: Essential, whenever it draws its own UI in a frame (a hover tint on
 * one of its title-screen buttons), makes the GUI pass start from a depth
 * buffer it restored rather than a cleared one. The background is the farthest
 * thing the menu draws, so against that buffer it failed the depth test and the
 * whole starfield went black for exactly the hovered frames. A background has
 * nothing behind it to be hidden by, so skipping the test costs nothing.
 *
 * <p>Built from the vanilla pipeline's own settings rather than restating them,
 * so a Minecraft update that changes the GUI shaders carries over. If any of it
 * can't be copied the vanilla pipeline is used as before.
 */
public final class NexoPipelines {
	public static final RenderPipeline BACKGROUND = build();

	private NexoPipelines() {
	}

	private static RenderPipeline build() {
		RenderPipeline base = RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND;
		try {
			RenderPipeline.Builder builder = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "pipeline/menu_background_no_depth"))
					.withVertexShader(base.getShaders().get(ShaderType.VERTEX))
					.withFragmentShader(base.getShaders().get(ShaderType.FRAGMENT))
					.withPrimitiveTopology(base.getPrimitiveTopology())
					.withPolygonMode(base.getPolygonMode())
					.withCull(base.isCull())
					.withPushConstantSize(base.pushConstantSize())
					.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,
							base.getDepthStencilState() != null && base.getDepthStencilState().writeDepth()));
			java.util.List<com.mojang.renderpearl.api.vertex.VertexFormat> bindings = base.getVertexFormatBindings();
			for (int i = 0; i < bindings.size(); i++) {
				if (bindings.get(i) != null) {
					builder.withVertexBinding(i, bindings.get(i));
				}
			}
			for (String flag : base.getShaderDefines().flags()) {
				builder.withShaderDefine(flag);
			}
			for (var define : base.getShaderDefines().values().entrySet()) {
				String value = define.getValue();
				if (value.matches("-?\\d+")) {
					builder.withShaderDefine(define.getKey(), Integer.parseInt(value));
				} else {
					builder.withShaderDefine(define.getKey(), Float.parseFloat(value));
				}
			}
			for (BindGroupLayout layout : base.getBindGroupLayouts()) {
				builder.withBindGroupLayout(layout);
			}
			for (int i = 0; i < base.getColorTargetStates().size(); i++) {
				builder.withColorTargetState(i, base.getColorTargetStates().get(i));
			}
			return builder.build();
		} catch (RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not build the depth-free menu background pipeline; using vanilla's.", e);
			return base;
		}
	}
}
