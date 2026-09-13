package dev.nexoclient.nexomod.paperserver.convert;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * Converts a singleplayer save into a Paper server's world directory, and
 * back. Verified 2026-08-22 against a real Paper 26.1.2 build and the real
 * vanilla 26.1.2 dedicated server (see {@code Mod/ROADMAP.md} Phase 7 and
 * the plan this shipped from): MC 26.1.2 unified every dimension under
 * {@code <world>/dimensions/<namespace>/<dim>/}, and a singleplayer save is
 * structurally identical to a dedicated server's {@code level-name}
 * directory — same {@code dimensions/}, same {@code level.dat}, same
 * {@code players/}. Conversion is therefore a plain recursive directory
 * copy with a renamed top-level folder, not a per-dimension remap.
 */
public final class WorldConverter {
	private WorldConverter() {}

	/**
	 * Copies {@code save} into {@code serverDir/levelName}. The original save
	 * is never touched — copy-then-verify-then-swap into place, so a failed or
	 * interrupted copy never leaves a half-written world at the final path.
	 */
	public static void toServer(Path save, Path serverDir, String levelName) throws IOException {
		if (!Files.isRegularFile(save.resolve("level.dat"))) {
			throw new IOException("Not a Minecraft save (no level.dat): " + save);
		}

		Path finalDest = serverDir.resolve(levelName);
		Path tempDest = serverDir.resolve(levelName + ".converting-" + System.nanoTime());
		Files.createDirectories(tempDest);
		copyTree(save, tempDest);

		if (!Files.isRegularFile(tempDest.resolve("level.dat"))) {
			deleteTree(tempDest);
			throw new IOException("Copy of " + save + " is missing level.dat after copying");
		}

		if (Files.exists(finalDest)) {
			deleteTree(finalDest);
		}
		Files.move(tempDest, finalDest, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Backs up the current singleplayer save, then overwrites it with
	 * {@code serverDir/levelName}. The backup happens unconditionally and
	 * first — even if the rest of this fails, nothing is lost.
	 *
	 * @return the path the pre-conversion save was backed up to
	 */
	public static Path toSingleplayer(Path serverDir, String levelName, Path save) throws IOException {
		Path source = serverDir.resolve(levelName);
		if (!Files.isRegularFile(source.resolve("level.dat"))) {
			throw new IOException("Not a Minecraft world (no level.dat): " + source);
		}

		Path backup = save.resolveSibling(save.getFileName() + "-nexo-backup-" + Instant.now().getEpochSecond());
		copyTree(save, backup);

		Path tempDest = save.resolveSibling(save.getFileName() + ".restoring-" + System.nanoTime());
		Files.createDirectories(tempDest);
		copyTree(source, tempDest);
		deleteTree(save);
		Files.move(tempDest, save, StandardCopyOption.ATOMIC_MOVE);

		return backup;
	}

	private static void copyTree(Path source, Path dest) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(dest.resolve(source.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, dest.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
