package dev.nexoclient.nexomod.wardrobe;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Tests a PNG before the native decoder sees it. The header's size alone is not
 * enough: stb accepts image data that inflates to far more than the image needs,
 * and a 1 MB file can make it allocate 1 GB. No Minecraft imports, so
 * {@code tools/skin-png-check/run.sh} can run it with plain javac.
 */
public final class NexoPngCheck {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

	private NexoPngCheck() {
	}

	/** A 64x64 or old-style 64x32 PNG, within the size limit. */
	public static boolean isSkin(byte[] png, int maxFile) {
		int[] size = size(png, maxFile);
		return size != null && size[0] == 64 && (size[1] == 64 || size[1] == 32) && dataFits(png, size);
	}

	/** Any PNG with both sides within {@code maxSide}. */
	public static boolean fits(byte[] png, int maxFile, int maxSide) {
		int[] size = size(png, maxFile);
		return size != null && size[0] <= maxSide && size[1] <= maxSide && dataFits(png, size);
	}

	private static int[] size(byte[] png, int maxFile) {
		if (png == null || png.length < 33 || png.length > maxFile) {
			return null;
		}
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (png[i] != SIGNATURE[i]) {
				return null;
			}
		}
		if (be32(png, 8) != 13 || !type(png, 12, "IHDR")) {
			return null;
		}
		int width = be32(png, 16);
		int height = be32(png, 20);
		return width > 0 && height > 0 ? new int[] {width, height} : null;
	}

	/** Inflates the image data here, stopping at twice the most any pixel format could need. */
	private static boolean dataFits(byte[] png, int[] size) {
		long limit = 2L * (size[0] * 8L + 1L) * size[1];
		byte[] sink = new byte[8192];
		long total = 0;
		Inflater inflater = new Inflater();
		try {
			int at = 8;
			while (at + 12 <= png.length) {
				int length = be32(png, at);
				if (length < 0 || length > png.length - at - 12) {
					return false;
				}
				// Apple's variant: stb then reads IDAT as raw deflate, not the zlib stream
				// measured here, so the same bytes could mean something much larger to it.
				if (type(png, at + 4, "CgBI")) {
					return false;
				}
				if (type(png, at + 4, "IDAT")) {
					inflater.setInput(png, at + 8, length);
					while (!inflater.needsInput() && !inflater.finished()) {
						int got = inflater.inflate(sink);
						total += got;
						if (total > limit || (got == 0 && inflater.needsDictionary())) {
							return false;
						}
					}
				}
				at += 12 + length;
			}
			return total > 0;
		} catch (DataFormatException e) {
			return false;
		} finally {
			inflater.end();
		}
	}

	private static boolean type(byte[] b, int at, String name) {
		for (int i = 0; i < 4; i++) {
			if (b[at + i] != name.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int be32(byte[] b, int at) {
		return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
	}
}
