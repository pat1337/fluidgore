import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An animal's data sheet ({@code sheets/<animal>.sheet}): what it weighs, how much blood it holds and what each part of
 * it weighs, each number with the reference it was read from. Reads the file, works out every part's kilograms and
 * checks that the sheet holds together: every number names a source that is listed, every part has a place in the
 * source, the parts of a whole add up to it, and what the references leave unlisted stays inside the bound the sheet
 * gives. The anatomy tools read it ({@code tools/SheepAnatomyModels.java}) and write the Java constants the mod uses
 * and the sheet as a page for people. Run alone to check a sheet:
 * <pre>java tools/Sheet.java sheets/sheep.sheet</pre>
 */
public final class Sheet {
	/** One part of the live weight (see the rules at the head of a sheet's {@code [parts]}). */
	static final class Part {
		final String key;
		final String parent;
		final String rule;
		final String number;
		final String source;
		final String where;
		final List<Part> children = new ArrayList<>();
		/** Kilograms, worked out by {@link #weigh}; NaN for a part outside the live weight. */
		double kg = Double.NaN;

		Part(String key, String parent, String rule, String number, String source, String where) {
			this.key = key;
			this.parent = parent;
			this.rule = rule;
			this.number = number;
			this.source = source;
			this.where = where;
		}

		boolean outside() {
			return rule.equals("outside");
		}
	}

	/** The key of the whole animal, the parent of the top parts. */
	static final String LIVE = "live";
	/** How far the parts of a whole may miss it when none of them is its rest: a thousandth, for rounded percentages. */
	static final double SLACK = 0.001;

	final Path file;
	String id;
	String subject;
	final Map<String, String> sources = new LinkedHashMap<>();
	/** The {@code [body]} rows: key to {value, source, where}. */
	final Map<String, String[]> body = new LinkedHashMap<>();
	final Map<String, Part> parts = new LinkedHashMap<>();
	/** The {@code [objects]} rows: name (or {@code table/name}, {@code table/*}) to {part, note}. */
	final Map<String, String[]> objects = new LinkedHashMap<>();
	double liveKg;
	double bloodMlPerKg;

	private Sheet(Path file) {
		this.file = file;
	}

	public static void main(String[] args) throws IOException {
		Sheet sheet;
		try {
			sheet = read(Path.of(args.length > 0 ? args[0] : "sheets/sheep.sheet"));
		} catch (IllegalStateException e) {
			System.err.println(e.getMessage());
			System.exit(1);
			return;
		}

		System.out.printf(Locale.ROOT, "%s: %.1f kg, %.0f ml of blood a kilogram (%.2f L)%n", sheet.id, sheet.liveKg, sheet.bloodMlPerKg,
				sheet.bloodLitres());
		for (Part p : sheet.parts.values()) {
			if (p.parent.equals(LIVE) && !p.outside()) {
				System.out.printf(Locale.ROOT, "%-28s %8.3f kg%n", p.key, p.kg);
				for (Part child : p.children) {
					System.out.printf(Locale.ROOT, "  %-26s %8.3f kg%n", child.key, child.kg);
				}
			}
		}

		System.out.println(sheet.parts.size() + " parts, " + sheet.objects.size() + " object rows: the sheet holds together");
	}

	/** Reads and checks a sheet. Throws {@link IllegalStateException} naming what is wrong with it. */
	static Sheet read(Path file) throws IOException {
		Sheet sheet = new Sheet(file);
		String section = "";
		int n = 0;
		for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			if (line.startsWith("[") && line.endsWith("]")) {
				section = line.substring(1, line.length() - 1);
				continue;
			}

			String[] c = line.split("\\|", -1);
			for (int i = 0; i < c.length; i++) {
				c[i] = c[i].strip();
			}

			String at = file + ":" + n + ": ";
			switch (section) {
				case "animal" -> {
					need(c.length == 2, at + "an [animal] row is key | value");
					switch (c[0]) {
						case "id" -> sheet.id = c[1];
						case "subject" -> sheet.subject = c[1];
						default -> throw new IllegalStateException(at + "unknown [animal] key " + c[0]);
					}
				}
				case "sources" -> {
					need(c.length == 2 && !c[1].isEmpty(), at + "a [sources] row is key | reference");
					need(sheet.sources.put(c[0], c[1]) == null, at + "source " + c[0] + " is listed twice");
				}
				case "body" -> {
					need(c.length == 4, at + "a [body] row is key | value | source | where in the source");
					need(sheet.body.put(c[0], new String[] {c[1], c[2], c[3]}) == null, at + c[0] + " is given twice");
				}
				case "parts" -> {
					need(c.length == 6, at + "a [parts] row is key | parent | rule | number | source | where in the source");
					need(!c[0].equals(LIVE) && sheet.parts.put(c[0], new Part(c[0], c[1], c[2], c[3], c[4], c[5])) == null,
							at + "part " + c[0] + " is given twice");
				}
				case "objects" -> {
					need(c.length == 3, at + "an [objects] row is name | part | note");
					need(sheet.objects.put(c[0], new String[] {c[1], c[2]}) == null, at + "object " + c[0] + " is given twice");
				}
				default -> throw new IllegalStateException(at + "a row outside [animal], [sources], [body], [parts] or [objects]");
			}
		}

		sheet.check();
		return sheet;
	}

	private static void need(boolean ok, String what) {
		if (!ok) {
			throw new IllegalStateException(what);
		}
	}

	/** Litres of blood in the whole animal. */
	double bloodLitres() {
		return liveKg * bloodMlPerKg / 1000.0;
	}

	private void check() {
		String at = file + ": ";
		need(id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_]+"), at + "[animal] needs an id such as minecraft:sheep");
		need(subject != null && !subject.isEmpty(), at + "[animal] needs a subject: which animal was measured");
		for (String key : List.of("live_kg", "blood_ml_per_kg")) {
			need(body.containsKey(key), at + "[body] needs " + key);
		}

		for (Map.Entry<String, String[]> e : body.entrySet()) {
			sourced(e.getKey(), e.getValue()[1], e.getValue()[2]);
			need(number(e.getValue()[0]) > 0, at + e.getKey() + " must be more than nothing");
		}

		liveKg = number(body.get("live_kg")[0]);
		bloodMlPerKg = number(body.get("blood_ml_per_kg")[0]);
		for (Part p : parts.values()) {
			sourced(p.key, p.source, p.where);
			need(Set.of("kg", "percent", "share", "rest", "outside").contains(p.rule), at + p.key + " has an unknown rule: " + p.rule);
			need(p.parent.equals(LIVE) || parts.containsKey(p.parent), at + p.key + " is part of " + p.parent + ", which is not a part");
			need(p.outside() == p.number.isEmpty(), at + p.key + (p.outside() ? " lies outside the live weight and takes no number" : " needs a number"));
			if (!p.parent.equals(LIVE)) {
				Part parent = parts.get(p.parent);
				need(!p.outside() && !parent.outside(), at + p.key + ": a part outside the live weight has no parts and is no one's part");
				parent.children.add(p);
			}
		}

		weigh(LIVE, liveKg, parts.values().stream().filter(p -> p.parent.equals(LIVE) && !p.outside()).toList());
		for (Map.Entry<String, String[]> e : objects.entrySet()) {
			Part p = parts.get(e.getValue()[0]);
			need(p != null, at + "object " + e.getKey() + " belongs to " + e.getValue()[0] + ", which is not a part");
			need(p.children.isEmpty(), at + "object " + e.getKey() + " belongs to " + p.key + ", which is split into parts: name one of them");
		}
	}

	private void sourced(String key, String source, String where) {
		need(sources.containsKey(source), file + ": " + key + " names the source \"" + source + "\", which [sources] does not list");
		need(!where.isEmpty(), file + ": " + key + " does not say where in " + source + " its number stands");
	}

	/** Works out the kilograms of the {@code children} of a whole of {@code whole} kilograms, and theirs in turn. */
	private void weigh(String name, double whole, List<Part> children) {
		String at = file + ": ";
		Part rest = null;
		double sum = 0;
		for (Part p : children) {
			switch (p.rule) {
				case "kg" -> p.kg = number(p.number);
				case "percent" -> p.kg = whole * number(p.number) / 100.0;
				case "share" -> {
					need(p.number.contains("/") && !p.number.contains("*"), at + p.key + ": a share is a/b");
					p.kg = whole * number(p.number);
				}
				default -> {
					need(rest == null, at + name + " has two rests: " + (rest == null ? "" : rest.key) + " and " + p.key);
					rest = p;
					continue;
				}
			}

			need(p.kg > 0 && Double.isFinite(p.kg), at + p.key + " must weigh more than nothing");
			sum += p.kg;
		}

		if (rest != null) {
			rest.kg = whole - sum;
			double most = whole * number(rest.number) / 100.0;
			need(rest.kg >= 0, String.format(Locale.ROOT, "%sthe parts of %s weigh %.3f kg, more than its %.3f kg", at, name, sum, whole));
			need(rest.kg <= most, String.format(Locale.ROOT, "%s%s is %.3f kg, more than the %.3f kg (%s%% of %s) the sheet allows", at, rest.key,
					rest.kg, most, rest.number, name));
		} else {
			need(Math.abs(sum - whole) <= whole * SLACK,
					String.format(Locale.ROOT, "%sthe parts of %s weigh %.3f kg and it weighs %.3f kg: they must add up, or one must be its rest", at,
							name, sum, whole));
		}

		for (Part p : children) {
			if (!p.children.isEmpty()) {
				weigh(p.key, p.kg, p.children);
			}
		}
	}

	/** A sheet's number: a plain one, or {@code a/b}, or {@code a/b*c}. */
	private double number(String text) {
		try {
			double value = 1;
			for (String factor : text.split("\\*", -1)) {
				String[] ratio = factor.split("/", -1);
				need(ratio.length <= 2, file + ": cannot read the number " + text);
				double f = Double.parseDouble(ratio[0].strip());
				value *= ratio.length == 2 ? f / Double.parseDouble(ratio[1].strip()) : f;
			}

			return value;
		} catch (NumberFormatException e) {
			throw new IllegalStateException(file + ": cannot read the number " + text);
		}
	}

	/**
	 * The part a named piece of an anatomy table belongs to: the row for {@code table/name}, else for {@code table/*},
	 * else for {@code name}. Null if the sheet has none.
	 */
	Part partOf(String table, String name) {
		String[] row = objects.get(table + "/" + name);
		if (row == null) {
			row = objects.get(table + "/*");
		}

		if (row == null) {
			row = objects.get(name);
		}

		return row == null ? null : parts.get(row[0]);
	}

	/** Fails unless every one of a table's {@code names} belongs to a part. */
	void cover(String table, Set<String> names) {
		Set<String> missing = new TreeSet<>();
		for (String name : names) {
			if (partOf(table, name) == null) {
				missing.add(name);
			}
		}

		need(missing.isEmpty(), file + ": the " + table + " table names pieces the sheet's [objects] do not place: " + missing);
	}

	/** Fails if an [objects] row names a piece no table has: {@code named} holds every {@code name} and {@code table/name} there is. */
	void used(Set<String> named) {
		Set<String> unused = new TreeSet<>();
		for (String key : objects.keySet()) {
			if (!key.endsWith("/*") && !named.contains(key)) {
				unused.add(key);
			}
		}

		need(unused.isEmpty(), file + ": [objects] places pieces no anatomy table has: " + unused);
	}

	/** The entity type's path with a capital: "Sheep". */
	private String species() {
		String path = id.substring(id.indexOf(':') + 1);
		return Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	/** {@code anatomy/<Species>Sheet.java}: the numbers of the sheet the mod reads. */
	String java(String tool) {
		String[] live = body.get("live_kg");
		String[] blood = body.get("blood_ml_per_kg");
		return """
				package dev.fluidgore.anatomy;

				/**
				 * %s data sheet as the mod reads it, generated by {@code %s} from
				 * {@code %s}: do not edit, change the sheet and run the tool again.
				 * {@code docs/%s.md} is the same sheet for people, with every part of the animal and the reference
				 * each number was read from.
				 */
				public final class %sSheet {
					/**
					 * The grown animal's live weight, kilograms.
					 * %s
					 */
					public static final float LIVE_KG = %sF;
					/**
					 * The blood in its body, millilitres a kilogram of live weight.
					 * %s
					 */
					public static final float BLOOD_ML_PER_KG = %sF;

					private %sSheet() {
					}
				}
				""".formatted(article(species()), tool, unix(file), unix(file).replace(".sheet", ""), species(), cited(live), (float) liveKg, cited(blood),
				(float) bloodMlPerKg, species());
	}

	private static String article(String species) {
		return ("AEIOU".indexOf(species.charAt(0)) >= 0 ? "An " : "A ") + species.toLowerCase(Locale.ROOT) + "'s";
	}

	private static String unix(Path path) {
		return path.toString().replace('\\', '/');
	}

	/** "Hansard (1956), Table I: ..." from a [body] row, wrapped for a Javadoc line. */
	private String cited(String[] row) {
		return author(row[1]) + ", " + row[2];
	}

	/** The author and year a reference starts with: "Butterfield, R. M. (1988)" gives "Butterfield (1988)". */
	private String author(String source) {
		String reference = sources.get(source);
		int comma = reference.indexOf(',');
		int open = reference.indexOf('(');
		int close = reference.indexOf(')');
		need(comma > 0 && open > comma && close > open, file + ": the reference " + source + " must start with Author, initials (year)");
		return reference.substring(0, comma) + " " + reference.substring(open, close + 1);
	}

	/**
	 * The sheet as a page: the animal, every part with its kilograms, how the number was come by and where it stands in
	 * its source, and beside each the litres its pieces are drawn with ({@code drawn}: part key to litres, the grown
	 * animal's tables) so the picture can be held against the weight.
	 */
	String markdown(String tool, Map<String, Double> drawn, Map<String, Set<String>> names) {
		StringBuilder sb = new StringBuilder();
		String species = species().toLowerCase(Locale.ROOT);
		sb.append("# The ").append(species).append("'s data sheet\n\n");
		sb.append("Generated by `").append(tool).append("` from [`").append(unix(file)).append("`](../../").append(unix(file))
				.append("); do not edit this page, change the sheet and run the tool again. The tool fails if a number has no source,\n")
				.append("if the parts do not add up to the live weight, or if a named piece of the anatomy belongs to no part.\n\n");
		sb.append("## The animal\n\n").append(subject).append("\n\n");
		sb.append("| | Value | Source |\n|---|---|---|\n");
		sb.append("| Live weight | ").append(f(liveKg, 1)).append(" kg | ").append(cited(body.get("live_kg"))).append(" |\n");
		sb.append("| Blood | ").append(f(bloodMlPerKg, 0)).append(" ml a kilogram, ").append(f(bloodLitres(), 2)).append(" L in the body | ")
				.append(cited(body.get("blood_ml_per_kg"))).append(" |\n\n");

		sb.append("## What it weighs, part by part\n\n");
		sb.append("\"Drawn\" is the volume of the part's pieces in the grown animal's anatomy tables, at the scale each table is drawn to.\n")
				.append("The last column is the one over the other: the litres a part is drawn with for each kilogram it weighs. A lung is\n")
				.append("full of air and a stomach is hollow, so they take more room than their tissue weighs.\n\n");
		sb.append("| Part | kg | % of live weight | How | Where the number stands | Drawn, L | L a kg |\n|---|---:|---:|---|---|---:|---:|\n");
		double total = 0;
		for (Part p : parts.values()) {
			if (p.parent.equals(LIVE) && !p.outside()) {
				row(sb, p, "", drawn);
				for (Part child : p.children) {
					row(sb, child, "– ", drawn);
				}
			}
		}

		for (Part p : parts.values()) {
			if (!p.outside() && p.children.isEmpty()) {
				total += drawn.getOrDefault(p.key, 0.0);
			}
		}

		sb.append("| **Whole animal** | **").append(f(liveKg, 2)).append("** | **100** | | | **").append(f(total, 1)).append("** | **")
				.append(f(total / liveKg, 2)).append("** |\n\n");

		sb.append("## Outside the live weight\n\n");
		for (Part p : parts.values()) {
			if (p.outside()) {
				sb.append("- **").append(label(p)).append("**: ").append(p.where);
				Set<String> in = names.get(p.key);
				if (in != null) {
					sb.append(" Pieces: ").append(String.join(", ", in).replace('_', ' ')).append('.');
				}

				Double litres = drawn.get(p.key);
				if (litres != null) {
					sb.append(" Drawn: ").append(f(litres, 2)).append(" L.");
				}

				sb.append('\n');
			}
		}

		sb.append("\n## The pieces of each part\n\nThe names are those of the anatomy tables. Pieces listed together are weighed together: the references give no\n")
				.append("weight for each alone.\n\n| Part | Pieces |\n|---|---|\n");
		for (Part p : parts.values()) {
			Set<String> in = names.get(p.key);
			if (!p.outside() && in != null) {
				sb.append("| ").append(label(p)).append(" | ").append(String.join(", ", in).replace('_', ' ')).append(" |\n");
			}
		}

		List<String> none = new ArrayList<>();
		for (Part p : parts.values()) {
			if (!p.outside() && p.children.isEmpty() && !names.containsKey(p.key)) {
				none.add(label(p));
			}
		}

		sb.append("\nWeighed, but not drawn as pieces: ").append(String.join(", ", none)).append(".\n");
		sb.append("\n## Sources\n\n");
		sources.forEach((key, reference) -> sb.append("- **").append(key).append("**: ").append(reference).append('\n'));
		return sb.toString();
	}

	private void row(StringBuilder sb, Part p, String indent, Map<String, Double> drawn) {
		Double litres = p.children.isEmpty() ? drawn.get(p.key) : null;
		sb.append("| ").append(indent).append(label(p)).append(" | ").append(f(p.kg, 3)).append(" | ").append(f(100 * p.kg / liveKg, 2)).append(" | ")
				.append(how(p)).append(" | ").append(p.source).append(": ").append(p.where).append(" | ")
				.append(litres == null ? p.children.isEmpty() ? "not drawn" : "" : f(litres, 2)).append(" | ")
				.append(litres == null ? "" : f(litres / p.kg, 2)).append(" |\n");
	}

	/** A part's key as words: "m_biceps_femoris" under muscle is "biceps femoris", "fat_kidney" is "kidney fat". */
	private static String label(Part p) {
		String key = p.key;
		if (key.startsWith("m_")) {
			key = key.substring(2);
		} else if (key.startsWith(p.parent + "_")) {
			key = key.substring(p.parent.length() + 1);
		} else if (key.startsWith("fat_")) {
			key = key.substring(4) + " fat";
		} else if (key.startsWith("content_")) {
			key = "content of the " + key.substring(8);
		}

		return key.replace('_', ' ');
	}

	private String how(Part p) {
		String of = p.parent.equals(LIVE) ? "live weight" : p.parent.replace('_', ' ');
		return switch (p.rule) {
			case "kg" -> p.number.contains("/") ? "worked out: " + p.number.replace("*", " × ") : "as weighed";
			case "percent" -> p.number + "% of " + of;
			case "share" -> p.number + " of " + of;
			default -> "the rest of " + of;
		};
	}

	private static String f(double value, int places) {
		return String.format(Locale.ROOT, "%." + places + "f", value);
	}
}
