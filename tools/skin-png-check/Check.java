import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import dev.nexoclient.nexomod.wardrobe.NexoPngCheck;

public class Check {
	static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
		out.write(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length});
		out.write(type.getBytes());
		out.write(data);
		CRC32 crc = new CRC32();
		crc.update(type.getBytes());
		crc.update(data);
		long c = crc.getValue();
		out.write(new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
	}

	static byte[] png(int w, int h, int rawBytes) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
		chunk(out, "IHDR", new byte[] {(byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w, (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h, 8, 6, 0, 0, 0});
		Deflater deflater = new Deflater(9);
		deflater.setInput(new byte[rawBytes]);
		deflater.finish();
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		while (!deflater.finished()) {
			idat.write(buf, 0, deflater.deflate(buf));
		}
		chunk(out, "IDAT", idat.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	static void expect(boolean actual, boolean wanted, String what) {
		if (actual != wanted) {
			throw new AssertionError(what);
		}
		System.out.println("ok: " + what);
	}

	public static void main(String[] args) throws Exception {
		int max = 256 * 1024;
		expect(NexoPngCheck.isSkin(png(64, 64, (64 * 4 + 1) * 64), max), true, "64x64 skin");
		expect(NexoPngCheck.isSkin(png(64, 32, (64 * 4 + 1) * 32), max), true, "64x32 skin");
		expect(NexoPngCheck.isSkin(png(128, 128, 1000), max), false, "wrong size");
		expect(NexoPngCheck.isSkin(png(64, 64, 100_000_000), max), false, "decompression bomb");
		expect(NexoPngCheck.isSkin(new byte[100], max), false, "not a PNG");
		byte[] good = png(64, 64, 1000);
		expect(NexoPngCheck.isSkin(java.util.Arrays.copyOf(good, 30), max), false, "truncated");
		expect(NexoPngCheck.fits(png(64, 32, (64 * 4 + 1) * 32), max, 512), true, "cape");
		expect(NexoPngCheck.fits(png(1024, 512, 1000), max, 512), false, "oversized cape");
		expect(NexoPngCheck.isSkin(good, 10), false, "over the file limit");
		byte[] clean = png(64, 64, 1000);
		byte[] tagged = new byte[clean.length + 16];
		System.arraycopy(clean, 0, tagged, 0, 33);
		ByteArrayOutputStream extra = new ByteArrayOutputStream();
		chunk(extra, "CgBI", new byte[4]);
		System.arraycopy(extra.toByteArray(), 0, tagged, 33, 16);
		System.arraycopy(clean, 33, tagged, 49, clean.length - 33);
		expect(NexoPngCheck.isSkin(tagged, max), false, "CgBI chunk");
	}
}
