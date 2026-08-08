package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import dev.nexoclient.nexomod.coords.CoordObfuscator;

/**
 * The bedrock/deepslate mix at the floor (Y -64..-60) is derived from the
 * world seed, so on a server whose seed is known a screenshot of that pattern
 * can be brute-force matched to recover real coordinates — same leak class as
 * the block-rotation pattern, but immune to re-seeding because the server
 * decides which blocks are bedrock. Hiding is the only option: when the
 * disguise is on, every non-air block at or below
 * {@link CoordObfuscator#BEDROCK_FLOOR_MAX_Y} renders as bedrock.
 *
 * {@code RenderSectionRegion} is the section snapshot the meshing threads
 * read, so replacing states here affects chunk meshes only — collision,
 * interaction, sounds, and everything server-side still see the real blocks.
 * Face culling stays self-consistent because neighbors resolve through the
 * same getter, and both {@code SectionCompiler} and {@code FluidRenderer}
 * derive fluid geometry from these block states (never from
 * {@code getFluidState(BlockPos)}), so deep lava/water surfaces vanish with
 * the same check; {@code getFluidState} is patched anyway so no other region
 * caller can observe a real fluid below the threshold. Block entities stop
 * rendering too, since {@code SectionCompiler} only collects them when the
 * (now disguised) state reports one. Runs on meshing worker threads;
 * {@link CoordObfuscator#bedrockFloorActive()} is a volatile read.
 */
@Mixin(RenderSectionRegion.class)
public abstract class BedrockFloorDisguiseMixin {
	@Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
	private void nexomod$disguiseDeepBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (CoordObfuscator.bedrockFloorActive()
				&& pos.getY() <= CoordObfuscator.BEDROCK_FLOOR_MAX_Y
				&& !cir.getReturnValue().isAir()) {
			cir.setReturnValue(Blocks.BEDROCK.defaultBlockState());
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void nexomod$suppressDeepFluids(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (CoordObfuscator.bedrockFloorActive() && pos.getY() <= CoordObfuscator.BEDROCK_FLOOR_MAX_Y) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}
}
