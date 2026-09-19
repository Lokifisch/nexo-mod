package dev.nexoclient.nexomod.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import dev.nexoclient.nexomod.norender.NexoNoRender;

/**
 * Feeds Dynamic No Render's own profiler — see {@code NexoNoRender}'s class
 * doc for why this exists instead of reading {@code Minecraft.fpsPieProfiler}
 * directly (that field turned out to be hard-gated to F3 being open and
 * crashed when it wasn't).
 *
 * <p>Redirects the one call in {@code ClientLevel.tickEntities()} that ticks
 * every entity, rather than wrapping the whole method: this is the exact
 * window {@code ClientLevel.tickNonPassenger}'s own per-entity-type profiler
 * sections are pushed and popped inside, confirmed by decompiling it.
 */
@Mixin(ClientLevel.class)
public class NoRenderProfilerMixin {
	@Redirect(method = "tickEntities",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
	private void nexomod$tickEntities(EntityTickList entities, Consumer<Entity> action) {
		NexoNoRender.tickEntitiesProfiled(entities, action);
	}
}
