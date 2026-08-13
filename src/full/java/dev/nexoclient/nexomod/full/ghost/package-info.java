/**
 * Ghost mode: one key that takes every trace of Nexo off the screen, including
 * the full jar's informational overlays.
 *
 * <p>It is the screenshot toggle in {@code dev.nexoclient.nexomod.hud} plus the
 * things only this jar draws, and it deliberately shares that class's flag
 * rather than adding a second one — a parallel switch would have to be checked
 * everywhere the first one is, and the first path someone forgot would be a
 * frame with half the mod still visible.
 *
 * <p>This package is inside {@code src/full}, so nothing in it ever reaches the
 * {@code nexomod-light} jar.
 *
 * <p>Register whatever you add from
 * {@link dev.nexoclient.nexomod.full.NexoFullFeatures}; mixins go in
 * {@code dev.nexoclient.nexomod.full.mixin} and are listed in
 * {@code src/full/resources/nexomod-full.mixins.json}.
 */
package dev.nexoclient.nexomod.full.ghost;
