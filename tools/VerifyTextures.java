import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Guards the generated textures against changing under new work: a manifest of what they were (a SHA-256 of every PNG,
 * and of every 16px tile of anatomy.png, its pixels' colours), and a check that what is there now still matches it.
 * Adding a tile in a free slot of anatomy.png (as the 3.3 cut-surface tiles 52 and 53 were) changes the file's bytes but
 * no listed tile: the check names the new tiles and passes. A changed tile, or any other PNG changed, fails.
 *
 * <pre>
 * java tools/VerifyTextures.java write  &lt;assets dir&gt; tools/texture-manifest.txt   # record what is there now
 * java tools/VerifyTextures.java check  &lt;assets dir&gt; tools/texture-manifest.txt   # exit 1 on any change
 * </pre>
 * {@code <assets dir>} is {@code src/main/resources/assets/fluidgore}, or a folder {@code GenerateTextures} wrote, to
 * check the generator still makes the same.
 */
public class VerifyTextures {
	private static final String ANATOMY = "textures/misc/anatomy.png";

	public static void main(String[] args) throws Exception {
		if (args.length != 3 || !(args[0].equals("write") || args[0].equals("check"))) {
			System.err.println("usage: java tools/VerifyTextures.java write|check <assets dir> <manifest>");
			System.exit(2);
		}

		Path root = Path.of(args[1]);
		Path manifest = Path.of(args[2]);
		Map<String, String> now = hashes(root);
		if (args[0].equals("write")) {
			List<String> lines = new ArrayList<>();
			lines.add("# Fluid Gore generated textures as they were: SHA-256 of each PNG, and of each anatomy.png tile's pixels.");
			lines.add("# Checked by tools/VerifyTextures.java; new tiles in free slots are allowed, changed ones are not.");
			now.forEach((key, hash) -> lines.add(key + " " + hash));
			Files.write(manifest, lines);
			System.out.println("wrote " + now.size() + " entries to " + manifest);
			return;
		}

		Map<String, String> was = new LinkedHashMap<>();
		for (String line : Files.readAllLines(manifest)) {
			if (!line.isBlank() && !line.startsWith("#")) {
				int space = line.lastIndexOf(' ');
				was.put(line.substring(0, space), line.substring(space + 1));
			}
		}

		int changed = 0;
		for (Map.Entry<String, String> entry : was.entrySet()) {
			String key = entry.getKey();
			if (key.equals("file " + ANATOMY)) {
				// Its tiles are checked one by one: a new tile changes the file but none of these.
				continue;
			}

			String hash = now.get(key);
			if (hash == null) {
				System.out.println("MISSING " + key);
				changed++;
			} else if (!hash.equals(entry.getValue())) {
				System.out.println("CHANGED " + key);
				changed++;
			}
		}

		TreeSet<String> added = new TreeSet<>();
		for (String key : now.keySet()) {
			if (!was.containsKey(key) && key.startsWith("tile ")) {
				added.add(key);
			} else if (!was.containsKey(key)) {
				System.out.println("NEW FILE " + key);
			}
		}

		long tiles = was.keySet().stream().filter(k -> k.startsWith("tile ")).count();
		long files = was.keySet().stream().filter(k -> k.startsWith("file ")).count();
		System.out.println("checked " + files + " files and " + tiles + " anatomy tiles: " + (changed == 0 ? "unchanged" : changed + " changed")
				+ (added.isEmpty() ? "" : "; new " + String.join(", ", added)));
		System.exit(changed == 0 ? 0 : 1);
	}

	/** "file <path>" for every PNG under {@code root}, and "tile anatomy <n>" for every anatomy.png tile that is not empty. */
	private static Map<String, String> hashes(Path root) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		List<Path> pngs;
		try (Stream<Path> walk = Files.walk(root)) {
			pngs = walk.filter(p -> p.toString().endsWith(".png")).sorted().toList();
		}

		for (Path png : pngs) {
			String name = root.relativize(png).toString().replace(File.separatorChar, '/');
			out.put("file " + name, sha(Files.readAllBytes(png)));
			if (name.equals(ANATOMY)) {
				BufferedImage image = ImageIO.read(png.toFile());
				int columns = image.getWidth() / 16;
				int rows = image.getHeight() / 16;
				for (int t = 0; t < columns * rows; t++) {
					byte[] pixels = tile(image, t % columns * 16, t / columns * 16);
					if (!empty(pixels)) {
						out.put(String.format("tile anatomy %02d", t), sha(pixels));
					}
				}
			}
		}

		return out;
	}

	private static byte[] tile(BufferedImage image, int x0, int y0) {
		byte[] out = new byte[16 * 16 * 4];
		int i = 0;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int argb = image.getRGB(x0 + x, y0 + y);
				out[i++] = (byte) (argb >>> 24);
				out[i++] = (byte) (argb >>> 16);
				out[i++] = (byte) (argb >>> 8);
				out[i++] = (byte) argb;
			}
		}

		return out;
	}

	/** A tile no generator wrote: fully transparent. */
	private static boolean empty(byte[] pixels) {
		for (int i = 0; i < pixels.length; i += 4) {
			if (pixels[i] != 0) {
				return false;
			}
		}

		return true;
	}

	private static String sha(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}
}
