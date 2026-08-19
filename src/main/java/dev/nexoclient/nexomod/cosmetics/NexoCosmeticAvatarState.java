package dev.nexoclient.nexomod.cosmetics;

/**
 * Duck-typed onto {@code AvatarRenderState} by
 * {@code mixin.AvatarRenderStateCosmeticMixin}. Vanilla's render state
 * carries a {@code skin} and nothing about a third-party cosmetics service,
 * so the equipped cape id resolved for this frame's player has nowhere else
 * to ride along to {@link NexoCosmeticsCapeLayer#submit} except a field added
 * here.
 */
public interface NexoCosmeticAvatarState {
	/** The catalog id of the cape to draw this frame, or -1 for none. */
	int nexomod$capeCosmeticId();

	void nexomod$setCapeCosmeticId(int id);
}
