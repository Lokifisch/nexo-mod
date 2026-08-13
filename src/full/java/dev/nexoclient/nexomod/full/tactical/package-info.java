/**
 * Tactical/combat-side features: today the sound direction indicator.
 *
 * <p>This package is inside {@code src/full}, so nothing in it ever reaches the
 * {@code nexomod-light} jar. That is why it exists as its own package: anything
 * that reads the world or the other players in it for an advantage belongs on
 * this side of the split.
 *
 * <p>The sound radar turns {@code ClientboundSoundPacket} into a bearing and a
 * distance. Vanilla already draws a coarse direction on subtitles, so the
 * feature is not new in kind — but a bearing on a ring is information the client
 * has and does not show, which is exactly the line this side holds.
 *
 * <p>Register whatever you add from
 * {@link dev.nexoclient.nexomod.full.NexoFullFeatures}; mixins go in
 * {@code dev.nexoclient.nexomod.full.mixin} and are listed in
 * {@code src/full/resources/nexomod-full.mixins.json}.
 */
package dev.nexoclient.nexomod.full.tactical;
