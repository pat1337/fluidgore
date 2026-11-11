import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prints one version's section of the changelog as the text of a GitHub release. The changelog wraps its lines, and a
 * release page breaks a line wherever its text does, so every item is put back on one line.
 *
 * <pre>
 * java tools/ReleaseNotes.java 4.0.0                  # from CHANGELOG.md; "v4.0.0" and "Unreleased" work too
 * java tools/ReleaseNotes.java 4.0.0 &lt;changelog&gt;      # from another file
 * </pre>
 * Exits with 1 when the changelog has no such section, or an empty one.
 */
public class ReleaseNotes {
	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 2) {
			System.err.println("usage: java tools/ReleaseNotes.java <version> [changelog]");
			System.exit(2);
		}

		String version = args[0].startsWith("v") ? args[0].substring(1) : args[0];
		Path changelog = Path.of(args.length > 1 ? args[1] : "CHANGELOG.md");

		List<String> notes = new ArrayList<>();
		boolean inside = false;
		for (String line : Files.readAllLines(changelog)) {
			if (line.startsWith("## ")) {
				if (inside) {
					break;
				}
				inside = named(line).equalsIgnoreCase(version);
			} else if (inside) {
				add(notes, line.stripTrailing());
			}
		}

		while (!notes.isEmpty() && notes.getFirst().isEmpty()) {
			notes.removeFirst();
		}
		while (!notes.isEmpty() && notes.getLast().isEmpty()) {
			notes.removeLast();
		}
		if (notes.isEmpty()) {
			System.err.println(changelog + " has nothing under a \"## " + version + "\" heading");
			System.exit(1);
		}

		// UTF-8 and plain newlines on every system, so the same text comes out wherever this runs.
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
		notes.forEach(line -> out.print(line + "\n"));
		out.flush();
	}

	/** The version a release heading names: "## 4.0.0 - 2026-11-05" gives "4.0.0", "## [Unreleased]" gives "Unreleased". */
	private static String named(String heading) {
		String name = heading.substring(3).strip();
		int end = name.indexOf(' ');
		if (end >= 0) {
			name = name.substring(0, end);
		}
		if (name.startsWith("[") && name.endsWith("]")) {
			name = name.substring(1, name.length() - 1);
		}
		return name.toLowerCase(Locale.ROOT);
	}

	/** Adds a line of the section, joining it to the line before when it only carries on that line's sentence. */
	private static void add(List<String> notes, String line) {
		String text = line.strip();
		String before = notes.isEmpty() ? "" : notes.getLast();
		boolean starts = text.isEmpty() || text.startsWith("#") || item(text);
		// An indented line carries on the item above it; a line at the margin carries on a paragraph, never an item.
		boolean carriesOn = !starts && !before.isEmpty() && !before.startsWith("#")
				&& (line.startsWith(" ") || !item(before.strip()));
		if (carriesOn) {
			notes.set(notes.size() - 1, before + " " + text);
		} else {
			notes.add(line);
		}
	}

	private static boolean item(String text) {
		return text.startsWith("- ") || text.startsWith("* ");
	}
}
