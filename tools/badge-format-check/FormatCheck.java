package dev.nexoclient.nexomod.badge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Answers, for each UUID given, whether the mod's own roster code finds it in
 * the blob at argv[0]. The blob is produced by the real service, so running
 * this is a check that the two implementations agree on both halves of the
 * format — the hash and the sort order.
 *
 * <p>Compiles against BadgeRosterFormat alone, which is why that class does not
 * depend on Fabric or Minecraft.
 */
public final class FormatCheck {
	public static void main(String[] args) throws Exception {
		byte[] roster = Files.readAllBytes(Path.of(args[0]));
		for (int i = 1; i < args.length; i++) {
			UUID id = UUID.fromString(args[i]);
			System.out.println((BadgeRosterFormat.contains(roster, id) ? "HIT  " : "MISS ") + id);
		}
	}
}
