package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;

import dev.nexoclient.nexomod.coords.CoordObfuscator;

/**
 * Blocks with randomized model variants/rotations (grass tops, stone, sand…)
 * pick them from a hash of their position, so the rotation pattern in a
 * screenshot can be brute-force matched to recover real coordinates. When
 * block-rotation obscuring is on, feed that hash the same fake position the
 * F3 screen shows so the visible pattern matches the fake coordinates.
 *
 * {@code BlockStateBase.getSeed(BlockPos)} is the single dispatcher every
 * caller goes through, and shifting its argument (rather than replacing the
 * result) keeps per-block overrides like {@code DoorBlock}/{@code BedBlock}
 * getSeed (which normalize both halves to one position) working on the
 * shifted position. Its only callers in 26.1.2 are render-side
 * ({@code SectionCompiler}, {@code BlockFeatureRenderer},
 * {@code LevelRenderer}), so this is purely visual — nothing server-side or
 * world-gen reads it, even in singleplayer. Runs on chunk-meshing worker
 * threads; {@link CoordObfuscator} is thread-safe for these calls.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockSeedObscureMixin {
	@ModifyVariable(method = "getSeed", at = @At("HEAD"), argsOnly = true)
	private BlockPos nexomod$obscureModelSeed(BlockPos pos) {
		return CoordObfuscator.rotationActive() ? CoordObfuscator.obscure(pos) : pos;
	}
}
