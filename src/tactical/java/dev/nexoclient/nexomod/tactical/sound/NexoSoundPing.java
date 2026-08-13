package dev.nexoclient.nexomod.tactical.sound;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * One sound the client was told about, kept only long enough to draw.
 *
 * @param x         world position the server sent, already un-quantised by
 *                  {@code ClientboundSoundPacket.getX()} — the packet stores
 *                  eighths of a block, which is why a ping never lines up
 *                  exactly with the entity that made it
 * @param y         see {@code x}
 * @param z         see {@code x}
 * @param source    the category, so the settings mask can drop whole classes of
 *                  sound without the HUD having to know what a note block is
 * @param subtitle  the same text vanilla subtitles show, or {@code null} when
 *                  the sound has none — a sound with no subtitle is one Mojang
 *                  decided is not worth announcing, and the label is simply
 *                  omitted rather than replaced with the raw sound id
 * @param timestamp {@code System.currentTimeMillis()} when the packet arrived,
 *                  used for the fade
 */
public record NexoSoundPing(double x, double y, double z, SoundSource source, Component subtitle, long timestamp) {
}
