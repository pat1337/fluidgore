import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Reads the game test's screenshots with the facts written beside them ({@code src/gametest/.../Lens.java}: the camera,
 * every body and piece in the picture with its screen box, blood, wounds and burns, and the blood in the world in
 * litres) and makes them easier to look at closely.
 *
 * <pre>
 * java tools/Lens.java look  &lt;screenshots&gt; [name part ...]   # per picture: marked.png, a crop of each subject, facts.txt
 * java tools/Lens.java sheet &lt;out.png&gt; &lt;picture ...&gt;          # tile pictures into one, each captioned
 * java tools/Lens.java sheet &lt;out.png&gt; &lt;screenshots&gt; &lt;name part ...&gt;
 * java tools/Lens.java diff  &lt;before&gt; &lt;after&gt; [out]           # what changed between two runs, picture and facts
 * </pre>
 * {@code <screenshots>} is {@code build/run/clientGameTest/screenshots}. {@code look} writes to {@code lens/} beside
 * it and {@code diff} to {@code lens-diff/} beside {@code <after>}. Keep a run to compare against by copying its
 * screenshots folder before the next run wipes it. Pictures are matched across runs by name, without the counter.
 */
public class Lens {
	/** A crop is scaled up by whole pixels to about this long side. */
	private static final int CROP_SIDE = 480;
	private static final int MOST_CROPS = 8;
	/** A pixel counts as changed when a channel moved by more than this. */
	private static final int CHANGED = 24;
	/** The widest a before | after | where picture is drawn. */
	private static final int DIFF_WIDE = 1560;
	private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
	private static final int LINE = 16;

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		if (args.length >= 2 && args[0].equals("look")) {
			look(Path.of(args[1]), List.of(args).subList(2, args.length));
		} else if (args.length >= 3 && args[0].equals("sheet")) {
			sheet(Path.of(args[1]), List.of(args).subList(2, args.length));
		} else if (args.length >= 3 && args[0].equals("diff")) {
			Path after = Path.of(args[2]);
			diff(Path.of(args[1]), after, args.length > 3 ? Path.of(args[3]) : after.toAbsolutePath().getParent().resolve("lens-diff"));
		} else {
			System.err.println("usage: java tools/Lens.java look <screenshots> [name part ...]");
			System.err.println("       java tools/Lens.java sheet <out.png> <picture ...> | <screenshots> <name part ...>");
			System.err.println("       java tools/Lens.java diff <before> <after> [out]");
			System.exit(2);
		}
	}

	// ---- look ----

	private static void look(Path dir, List<String> parts) throws IOException {
		Path out = dir.toAbsolutePath().getParent().resolve("lens");
		int done = 0;
		for (Path png : pictures(dir, parts)) {
			Path json = factsOf(png);
			if (!Files.exists(json)) {
				System.out.println(png.getFileName() + ": no facts");
				continue;
			}

			Map<String, Object> facts = obj(new Json(Files.readString(json)).value());
			BufferedImage picture = ImageIO.read(png.toFile());
			Path folder = out.resolve(stem(png));
			Files.createDirectories(folder);
			List<String> lines = describe(facts);
			List<String> crops = new ArrayList<>();
			int w = picture.getWidth();
			int h = picture.getHeight();
			for (Object o : list(facts.get("subjects"))) {
				Map<String, Object> s = obj(o);
				double[] box = box(s.get("box"));
				double area = (box[2] - box[0]) * (box[3] - box[1]);
				if (crops.size() >= MOST_CROPS || area > 0.5 || area <= 0.0) {
					continue;
				}

				String name = String.format(Locale.ROOT, "%02d-%s.png", num(s.get("n")).intValue(), what(s).replaceAll("[^A-Za-z0-9_]+", "_"));
				ImageIO.write(crop(picture, box), "png", folder.resolve(name).toFile());
				crops.add(name);
			}

			ImageIO.write(marked(picture, facts, lines), "png", folder.resolve("marked.png").toFile());
			List<String> text = new ArrayList<>(lines);
			text.add("crops: " + (crops.isEmpty() ? "none" : String.join(" ", crops)));
			Files.write(folder.resolve("facts.txt"), text);
			System.out.println(stem(png) + ": " + list(facts.get("subjects")).size() + " subjects, " + crops.size() + " crops -> " + folder
					+ " (" + w + "x" + h + ")");
			done++;
		}

		System.out.println(done + " pictures looked at");
	}

	/** The facts of one picture as lines: the camera and the blood first, then one line per subject. */
	private static List<String> describe(Map<String, Object> facts) {
		List<String> lines = new ArrayList<>();
		Map<String, Object> cam = obj(facts.get("camera"));
		String head = facts.get("shot") + "  gore " + facts.get("goreLevel");
		if (facts.containsKey("screen")) {
			head += "  screen " + facts.get("screen");
		}

		if (!cam.isEmpty()) {
			head += String.format(Locale.ROOT, "  camera %s yaw %.1f pitch %.1f fov %.1f", numbers(cam.get("pos")), num(cam.get("yaw")), num(cam.get("pitch")),
					num(cam.get("fov")));
		}

		lines.add(head);
		Map<String, Object> blood = obj(facts.get("blood"));
		if (!blood.isEmpty()) {
			Map<String, Object> seen = obj(blood.get("inView"));
			lines.add(String.format(Locale.ROOT, "blood: bodies lost %.2f L; on the ground %.2f L liquid + %.2f L soaked in %d cells (%d in view: %.2f + %.2f L); "
							+ "%d marks, %d drops in flight, %d clouds",
					num(blood.get("bodiesLostL")), num(blood.get("groundLiquidL")), num(blood.get("groundSoakedL")), num(blood.get("cells")).intValue(),
					num(seen.get("cells")).intValue(), num(seen.get("liquidL")), num(seen.get("soakedL")), num(blood.get("decals")).intValue(),
					num(blood.get("droplets")).intValue(), num(blood.get("clouds")).intValue()));
		}

		Map<String, Object> giblets = obj(facts.get("giblets"));
		if (!giblets.isEmpty()) {
			lines.add(String.format(Locale.ROOT, "giblets: %d (%d in view); subjects in view: %d", num(giblets.get("count")).intValue(),
					num(giblets.get("inView")).intValue(), num(facts.get("subjectsInView")).intValue()));
		}

		if (facts.containsKey("error")) {
			lines.add("error reading facts: " + facts.get("error"));
		}

		for (Object o : list(facts.get("subjects"))) {
			lines.add(subject(obj(o)));
		}

		return lines;
	}

	private static String subject(Map<String, Object> s) {
		StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%2d %-5s %-18s", num(s.get("n")).intValue(), s.get("kind"), what(s)));
		if ("body".equals(s.get("kind"))) {
			line.append(Boolean.TRUE.equals(s.get("alive")) ? " alive" : " dead ");
			List<Object> health = list(s.get("health"));
			if (health.size() == 2) {
				line.append(String.format(Locale.ROOT, " hp %.1f/%.0f", num(health.get(0)), num(health.get(1))));
			}

			Map<String, Object> blood = obj(s.get("blood"));
			line.append(String.format(Locale.ROOT, " blood lost %.2f of %.2f L", num(blood.get("lostL")), num(blood.get("holdsL"))));
			if (num(s.get("pallor")) > 0.0) {
				line.append(String.format(Locale.ROOT, " pallor %.2f", num(s.get("pallor"))));
			}

			if (num(s.get("char")) > 0.0) {
				line.append(String.format(Locale.ROOT, " char %.2f", num(s.get("char"))));
			}

			Map<String, Object> wounds = obj(s.get("wounds"));
			if (!wounds.isEmpty()) {
				line.append(" wounds ").append(list(wounds.get("each")));
				if (Boolean.TRUE.equals(wounds.get("throatCut"))) {
					line.append(" throat cut");
				}

				if (Boolean.TRUE.equals(wounds.get("lungPierced"))) {
					line.append(" lung pierced");
				}
			}

			if (s.containsKey("missing")) {
				line.append(" missing ").append(list(s.get("missing")));
			}

			Map<String, Object> corpse = obj(s.get("corpse"));
			if (!corpse.isEmpty()) {
				line.append(String.format(Locale.ROOT, " corpse %s age %d", corpse.get("kind"), num(corpse.get("age")).intValue()));
			}
		} else {
			line.append(" size ").append(numbers(s.get("size")));
			if (s.containsKey("tissue")) {
				line.append(" ").append(s.get("tissue"));
			}

			if (s.containsKey("owner")) {
				line.append(" of ").append(s.get("owner"));
			}

			line.append(Boolean.TRUE.equals(s.get("resting")) ? " resting" : " moving");
		}

		line.append(String.format(Locale.ROOT, " dist %.1f", num(s.get("dist"))));
		if (!Boolean.TRUE.equals(s.get("seen"))) {
			line.append(" HIDDEN by a block");
		}

		return line.toString();
	}

	/** What a subject is, in a word or two. */
	private static String what(Map<String, Object> s) {
		if ("body".equals(s.get("kind"))) {
			return s.get("type") + (Boolean.TRUE.equals(s.get("baby")) ? " (young)" : "");
		}

		for (String key : new String[]{"label", "organ", "tissue"}) {
			if (s.get(key) instanceof String text && !text.isBlank()) {
				return text;
			}
		}

		return "piece";
	}

	/** The picture with every subject boxed and numbered, and the facts written under it. */
	private static BufferedImage marked(BufferedImage picture, Map<String, Object> facts, List<String> lines) {
		int w = Math.max(picture.getWidth(), 900);
		int strip = lines.size() * LINE + 10;
		BufferedImage out = new BufferedImage(w, picture.getHeight() + strip, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(24, 24, 24));
		g.fillRect(0, 0, w, out.getHeight());
		g.drawImage(picture, 0, 0, null);
		g.setFont(FONT);
		int pw = picture.getWidth();
		int ph = picture.getHeight();
		Map<String, Object> blood = obj(obj(facts.get("blood")).get("inView"));
		if (blood.containsKey("box")) {
			double[] b = box(blood.get("box"));
			outline(g, new Color(255, 70, 70), (int) (b[0] * pw), (int) (b[1] * ph), (int) ((b[2] - b[0]) * pw), (int) ((b[3] - b[1]) * ph), true);
		}

		List<Object> subjects = list(facts.get("subjects"));
		for (int i = subjects.size() - 1; i >= 0; i--) {
			Map<String, Object> s = obj(subjects.get(i));
			double[] b = box(s.get("box"));
			Color color = !"body".equals(s.get("kind")) ? new Color(0, 220, 255) : Boolean.TRUE.equals(s.get("alive")) ? new Color(255, 220, 0) : new Color(255, 140, 0);
			int x = (int) (b[0] * pw);
			int y = (int) (b[1] * ph);
			outline(g, color, x, y, (int) ((b[2] - b[0]) * pw), (int) ((b[3] - b[1]) * ph), !Boolean.TRUE.equals(s.get("seen")));
			String tag = String.valueOf(num(s.get("n")).intValue());
			FontMetrics m = g.getFontMetrics();
			g.setColor(color);
			g.fillRect(x, y, m.stringWidth(tag) + 6, LINE);
			g.setColor(Color.BLACK);
			g.drawString(tag, x + 3, y + LINE - 4);
		}

		g.setColor(new Color(230, 230, 230));
		for (int i = 0; i < lines.size(); i++) {
			g.drawString(lines.get(i), 6, ph + 6 + (i + 1) * LINE - 4);
		}

		g.dispose();
		return out;
	}

	private static void outline(Graphics2D g, Color color, int x, int y, int w, int h, boolean dashed) {
		g.setColor(color);
		g.setStroke(dashed ? new BasicStroke(2.0F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0F, new float[]{6.0F, 4.0F}, 0.0F) : new BasicStroke(2.0F));
		g.drawRect(x, y, Math.max(1, w), Math.max(1, h));
		g.setStroke(new BasicStroke(1.0F));
	}

	/** {@code box} (0 to 1) cut out of {@code picture} with a margin round it, scaled up by whole pixels. */
	private static BufferedImage crop(BufferedImage picture, double[] box) {
		int pw = picture.getWidth();
		int ph = picture.getHeight();
		double bw = (box[2] - box[0]) * pw;
		double bh = (box[3] - box[1]) * ph;
		double margin = Math.max(16.0, 0.3 * Math.max(bw, bh));
		int x0 = (int) Math.max(0, Math.floor(box[0] * pw - margin));
		int y0 = (int) Math.max(0, Math.floor(box[1] * ph - margin));
		int x1 = (int) Math.min(pw, Math.ceil(box[2] * pw + margin));
		int y1 = (int) Math.min(ph, Math.ceil(box[3] * ph + margin));
		int w = Math.max(1, x1 - x0);
		int h = Math.max(1, y1 - y0);
		int k = Math.clamp(CROP_SIDE / Math.max(w, h), 1, 8);
		BufferedImage out = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h * k; y++) {
			for (int x = 0; x < w * k; x++) {
				out.setRGB(x, y, picture.getRGB(x0 + x / k, y0 + y / k));
			}
		}

		return out;
	}

	// ---- sheet ----

	private static void sheet(Path out, List<String> args) throws IOException {
		List<Path> files = new ArrayList<>();
		if (Files.isDirectory(Path.of(args.get(0)))) {
			files.addAll(pictures(Path.of(args.get(0)), args.subList(1, args.size())));
		} else {
			args.forEach(a -> files.add(Path.of(a)));
		}

		if (files.isEmpty()) {
			System.err.println("no pictures");
			System.exit(1);
		}

		int cols = (int) Math.ceil(Math.sqrt(files.size()));
		int rows = (files.size() + cols - 1) / cols;
		int tile = Math.min(800, 1600 / cols);
		List<BufferedImage> pictures = new ArrayList<>();
		int tileH = 0;
		for (Path f : files) {
			BufferedImage p = ImageIO.read(f.toFile());
			pictures.add(p);
			tileH = Math.max(tileH, (int) Math.round(p.getHeight() * (double) tile / p.getWidth()));
		}

		BufferedImage sheet = new BufferedImage(cols * tile, rows * (tileH + LINE + 4), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(24, 24, 24));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g.setFont(FONT);
		for (int i = 0; i < pictures.size(); i++) {
			BufferedImage p = pictures.get(i);
			int x = (i % cols) * tile;
			int y = (i / cols) * (tileH + LINE + 4);
			g.drawImage(p, x, y, tile, (int) Math.round(p.getHeight() * (double) tile / p.getWidth()), null);
			g.setColor(new Color(230, 230, 230));
			g.drawString(stem(files.get(i)), x + 4, y + tileH + LINE);
		}

		g.dispose();
		Path parent = out.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		ImageIO.write(sheet, "png", out.toFile());
		System.out.println(files.size() + " pictures -> " + out + " (" + sheet.getWidth() + "x" + sheet.getHeight() + ")");
	}

	// ---- diff ----

	private static void diff(Path before, Path after, Path out) throws IOException {
		Map<String, Path> was = byName(before);
		Map<String, Path> now = byName(after);
		Files.createDirectories(out);
		record Change(String name, double changed, double mean, List<String> facts) {
		}

		List<Change> changes = new ArrayList<>();
		for (Map.Entry<String, Path> e : now.entrySet()) {
			Path old = was.get(e.getKey());
			if (old == null) {
				continue;
			}

			BufferedImage a = ImageIO.read(old.toFile());
			BufferedImage b = ImageIO.read(e.getValue().toFile());
			List<String> facts = factsDiff(old, e.getValue());
			if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
				changes.add(new Change(e.getKey(), 1.0, 255.0, facts));
				continue;
			}

			int w = a.getWidth();
			int h = a.getHeight();
			long sum = 0;
			int moved = 0;
			int[] where = {w, h, -1, -1};
			BufferedImage heat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int p = a.getRGB(x, y);
					int q = b.getRGB(x, y);
					int d = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)), Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
					sum += d;
					int grey = ((q >> 16 & 255) + (q >> 8 & 255) + (q & 255)) / 9;
					if (d > CHANGED) {
						moved++;
						heat.setRGB(x, y, Math.min(255, 80 + d) << 16 | grey << 8 | grey);
						where[0] = Math.min(where[0], x);
						where[1] = Math.min(where[1], y);
						where[2] = Math.max(where[2], x);
						where[3] = Math.max(where[3], y);
					} else {
						heat.setRGB(x, y, grey << 16 | grey << 8 | grey);
					}
				}
			}

			double changed = moved / (double) (w * h);
			changes.add(new Change(e.getKey(), changed, sum / (double) (w * h), facts));
			if (changed > 0.002) {
				// Only the part that changed, with a margin, side by side; shrunk if three of it are wider than DIFF_WIDE.
				int x0 = Math.max(0, where[0] - 32);
				int y0 = Math.max(0, where[1] - 32);
				int cw = Math.min(w, where[2] + 33) - x0;
				int ch = Math.min(h, where[3] + 33) - y0;
				double k = Math.min(1.0, DIFF_WIDE / (3.0 * cw + 8.0));
				int tw = (int) Math.round(cw * k);
				int th = (int) Math.round(ch * k);
				BufferedImage three = new BufferedImage(3 * tw + 8, th, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = three.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, three.getWidth(), th);
				BufferedImage[] parts = {a, b, heat};
				for (int i = 0; i < 3; i++) {
					g.drawImage(parts[i].getSubimage(x0, y0, cw, ch), i * (tw + 4), 0, tw, th, null);
				}

				g.dispose();
				ImageIO.write(three, "png", out.resolve(e.getKey() + ".png").toFile());
			}
		}

		changes.sort((x, y) -> Double.compare(y.changed, x.changed));
		System.out.println("changed  mean  picture   (before | after | where it changed, in " + out + ")");
		for (Change c : changes) {
			System.out.printf(Locale.ROOT, "%6.2f%% %5.1f  %s%s%n", c.changed * 100.0, c.mean, c.name, c.changed > 0.002 ? "" : "  (same)");
			c.facts.forEach(f -> System.out.println("                 " + f));
		}

		was.keySet().stream().filter(k -> !now.containsKey(k)).forEach(k -> System.out.println("   gone        " + k));
		now.keySet().stream().filter(k -> !was.containsKey(k)).forEach(k -> System.out.println("   new         " + k));
	}

	/** Numbers and words in the facts that differ between two runs of one picture (not where things are on screen). */
	private static List<String> factsDiff(Path before, Path after) throws IOException {
		List<String> out = new ArrayList<>();
		if (!Files.exists(factsOf(before)) || !Files.exists(factsOf(after))) {
			return out;
		}

		Map<String, Object> a = flatten(obj(new Json(Files.readString(factsOf(before))).value()));
		Map<String, Object> b = flatten(obj(new Json(Files.readString(factsOf(after))).value()));
		for (Map.Entry<String, Object> e : b.entrySet()) {
			Object old = a.get(e.getKey());
			if (old == null) {
				out.add(e.getKey() + ": new, " + e.getValue());
			} else if (old instanceof Double x && e.getValue() instanceof Double y) {
				if (Math.abs(x - y) > 1.0E-3 + 0.01 * Math.max(Math.abs(x), Math.abs(y))) {
					out.add(String.format(Locale.ROOT, "%s: %s -> %s", e.getKey(), plain(x), plain(y)));
				}
			} else if (!old.equals(e.getValue())) {
				out.add(e.getKey() + ": " + old + " -> " + e.getValue());
			}
		}

		a.keySet().stream().filter(k -> !b.containsKey(k)).forEach(k -> out.add(k + ": gone"));
		return out;
	}

	/** The facts as one level of named values; each subject is named by what it is and its rank among its like. */
	private static Map<String, Object> flatten(Map<String, Object> facts) {
		Map<String, Object> out = new TreeMap<>();
		for (String top : new String[]{"goreLevel", "screen", "subjectsInView"}) {
			if (facts.containsKey(top)) {
				out.put(top, facts.get(top));
			}
		}

		flatten("blood", obj(facts.get("blood")), out);
		flatten("giblets", obj(facts.get("giblets")), out);
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (Object o : list(facts.get("subjects"))) {
			Map<String, Object> s = obj(o);
			String name = s.get("kind") + " " + what(s);
			int rank = seen.merge(name, 1, Integer::sum);
			Map<String, Object> rest = new LinkedHashMap<>(s);
			for (String skip : new String[]{"n", "box", "at", "dist", "id", "key", "age", "kind", "type", "label", "organ"}) {
				rest.remove(skip);
			}

			flatten(name + "#" + rank, rest, out);
		}

		return out;
	}

	private static void flatten(String prefix, Map<String, Object> values, Map<String, Object> out) {
		for (Map.Entry<String, Object> e : values.entrySet()) {
			String key = prefix + "." + e.getKey();
			if (e.getValue() instanceof Map<?, ?>) {
				flatten(key, obj(e.getValue()), out);
			} else if (!e.getKey().equals("box")) {
				out.put(key, e.getValue() instanceof List<?> l ? l.toString() : e.getValue());
			}
		}
	}

	// ---- files ----

	/** The PNGs in {@code dir} whose names hold any of {@code parts} (all of them if none), in name order. */
	private static List<Path> pictures(Path dir, List<String> parts) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> f.getFileName().toString().endsWith(".png"))
					.filter(f -> parts.isEmpty() || parts.stream().anyMatch(p -> f.getFileName().toString().contains(p)))
					.sorted().toList();
		}
	}

	/** The PNGs in {@code dir} by name without the screenshot counter. */
	private static Map<String, Path> byName(Path dir) throws IOException {
		Map<String, Path> out = new TreeMap<>();
		for (Path p : pictures(dir, List.of())) {
			out.put(stem(p).replaceFirst("^\\d+_", ""), p);
		}

		return out;
	}

	private static String stem(Path png) {
		String name = png.getFileName().toString();
		return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
	}

	private static Path factsOf(Path png) {
		return png.resolveSibling(stem(png) + ".json");
	}

	// ---- values ----

	@SuppressWarnings("unchecked")
	private static Map<String, Object> obj(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object o) {
		return o instanceof List<?> l ? (List<Object>) l : List.of();
	}

	private static Double num(Object o) {
		return o instanceof Double d ? d : 0.0;
	}

	private static double[] box(Object o) {
		List<Object> l = list(o);
		return l.size() == 4 ? new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2)), num(l.get(3))} : new double[4];
	}

	private static String numbers(Object o) {
		List<String> out = new ArrayList<>();
		list(o).forEach(v -> out.add(plain(num(v))));
		return "(" + String.join(", ", out) + ")";
	}

	private static String plain(double v) {
		return v == Math.rint(v) && Math.abs(v) < 1.0E9 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
	}

	/** Just enough JSON for the facts files: objects, arrays, strings, numbers (as Double), true, false and null. */
	private static final class Json {
		private final String s;
		private int i;

		Json(String s) {
			this.s = s;
		}

		Object value() {
			space();
			char c = s.charAt(i);
			if (c == '{') {
				Map<String, Object> m = new LinkedHashMap<>();
				i++;
				space();
				if (s.charAt(i) == '}') {
					i++;
					return m;
				}

				while (true) {
					space();
					String key = string();
					space();
					i++; // ':'
					m.put(key, value());
					space();
					if (s.charAt(i++) == '}') {
						return m;
					}
				}
			}

			if (c == '[') {
				List<Object> l = new ArrayList<>();
				i++;
				space();
				if (s.charAt(i) == ']') {
					i++;
					return l;
				}

				while (true) {
					l.add(value());
					space();
					if (s.charAt(i++) == ']') {
						return l;
					}
				}
			}

			if (c == '"') {
				return string();
			}

			if (s.startsWith("true", i)) {
				i += 4;
				return Boolean.TRUE;
			}

			if (s.startsWith("false", i)) {
				i += 5;
				return Boolean.FALSE;
			}

			if (s.startsWith("null", i)) {
				i += 4;
				return null;
			}

			int start = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
				i++;
			}

			return Double.parseDouble(s.substring(start, i));
		}

		private String string() {
			StringBuilder out = new StringBuilder();
			i++; // '"'
			while (s.charAt(i) != '"') {
				char c = s.charAt(i++);
				if (c == '\\') {
					char e = s.charAt(i++);
					switch (e) {
						case 'n' -> out.append('\n');
						case 't' -> out.append('\t');
						case 'u' -> {
							out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
							i += 4;
						}
						default -> out.append(e);
					}
				} else {
					out.append(c);
				}
			}

			i++;
			return out.toString();
		}

		private void space() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}
	}
}
