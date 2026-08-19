package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import dev.nexoclient.nexomod.cosmetics.NexoCosmeticAvatarState;

/** Backs {@link NexoCosmeticAvatarState} — see it for why this field exists. */
@Mixin(AvatarRenderState.class)
public class AvatarRenderStateCosmeticMixin implements NexoCosmeticAvatarState {
	@Unique
	private int nexomod$capeCosmeticId = -1;

	@Override
	public int nexomod$capeCosmeticId() {
		return nexomod$capeCosmeticId;
	}

	@Override
	public void nexomod$setCapeCosmeticId(int id) {
		this.nexomod$capeCosmeticId = id;
	}
}
