package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import dev.nexoclient.nexomod.coords.CoordObfuscator;

/**
 * {@link BedrockFloorDisguiseMixin}'s twin for Sodium, which replaces the
 * vanilla meshing pipeline entirely: its {@code ChunkBuilderMeshingTask} reads
 * blocks through {@code LevelSlice.getBlockState(III)} (never
 * {@code RenderSectionRegion}) and derives fluid geometry from those states,
 * while its {@code DefaultFluidRenderer} samples neighbor fluids via
 * {@code getFluidState(BlockPos)} — so those two methods are the complete
 * surface. The {@code BlockPos} overload of {@code getBlockState} delegates to
 * the {@code (III)} one, so patching {@code (III)} covers both. {@code @Pseudo}
 * skips this mixin silently when Sodium isn't installed; {@code remap = false}
 * because Sodium's methods aren't in the mappings and the Minecraft classes in
 * the descriptors already use runtime (Mojang) names.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice")
public abstract class BedrockFloorDisguiseSodiumMixin {
	@Inject(
			method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("RETURN"), cancellable = true, remap = false)
	private void nexomod$disguiseDeepBlocks(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		if (CoordObfuscator.bedrockFloorActive()
				&& y <= CoordObfuscator.BEDROCK_FLOOR_MAX_Y
				&& !cir.getReturnValue().isAir()) {
			cir.setReturnValue(Blocks.BEDROCK.defaultBlockState());
		}
	}

	@Inject(
			method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void nexomod$suppressDeepFluids(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (CoordObfuscator.bedrockFloorActive() && pos.getY() <= CoordObfuscator.BEDROCK_FLOOR_MAX_Y) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}
}
