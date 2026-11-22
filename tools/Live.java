import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Drives a live session of the game test ({@code ./gradlew runClientGameTest -Plive}, run from the project folder):
 * sends one command and prints the answer. Screenshots in the answer are marked and cropped with
 * {@code tools/Lens.java} when the project has it, and traces drawn with {@code tools/HerdMap.java}.
 *
 * <pre>
 * java tools/Live.java wait [seconds]    # until the session answers (it takes a while to open the world)
 * java tools/Live.java help              # the session's commands
 * java tools/Live.java shot              # any session command, e.g. cmd summon minecraft:sheep ~ ~ ~4
 * java tools/Live.java swap              # compile the sources changed since the session began and swap them in
 * </pre>
 * {@code swap} replaces the code of classes already loaded without restarting the game. The JVM only allows new method
 * bodies: a changed field, method signature, new method or new lambda, and any mixin, need a restart, and it says so.
 */
public class Live {
	private static final Path GAME = Path.of("build", "run", "clientGameTest");
	private static final Path SESSION = GAME.resolve("live.json");
	private static final Path WORK = Path.of("build", "live");
	private static final String[] SOURCE_SETS = {"main", "gametest"};

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("usage: java tools/Live.java wait [seconds] | swap | <session command ...>   (help lists them)");
			System.exit(2);
		}

		switch (args[0]) {
			case "wait" -> waitForSession(args.length > 1 ? Integer.parseInt(args[1]) : 300);
			case "swap" -> swap();
			default -> {
				String reply = send(String.join(" ", args));
				System.out.println(reply);
				pictures(reply);
				if (reply.contains("\"ok\": false")) {
					System.exit(1);
				}
			}
		}
	}

	private static String field(String json, String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}\\n]*)").matcher(json);
		if (!m.find()) {
			throw new IllegalStateException("no " + name + " in " + SESSION);
		}

		return m.group(1).trim();
	}

	private static String session() throws IOException {
		if (!Files.exists(SESSION)) {
			System.err.println("no live session: start one with ./gradlew runClientGameTest -Plive (from the project folder), then java tools/Live.java wait");
			System.exit(3);
		}

		return Files.readString(SESSION);
	}

	private static String send(String command) throws IOException {
		String about = session();
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(field(about, "port")))) {
			OutputStream out = socket.getOutputStream();
			out.write((field(about, "token") + " " + command + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			InputStream in = socket.getInputStream();
			ByteArrayOutputStream reply = new ByteArrayOutputStream();
			in.transferTo(reply);
			return reply.toString(StandardCharsets.UTF_8).trim();
		}
	}

	private static void waitForSession(int seconds) throws Exception {
		long until = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < until) {
			if (Files.exists(SESSION)) {
				try {
					String reply = send("where");
					System.out.println(reply);
					return;
				} catch (IOException e) {
					// Not listening yet.
				}
			}

			Thread.sleep(1000);
		}

		System.err.println("no live session answered within " + seconds + " s");
		System.exit(3);
	}

	/** Marks and crops the screenshots in an answer, tiles a set of them, draws a trace. */
	private static void pictures(String reply) throws Exception {
		List<String> pngs = new ArrayList<>();
		Matcher m = Pattern.compile("\"([^\"]+\\.png)\"").matcher(reply);
		while (m.find()) {
			pngs.add(m.group(1));
		}

		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		if (!pngs.isEmpty() && Files.exists(Path.of("tools", "Lens.java"))) {
			Path dir = Path.of(pngs.getFirst()).getParent();
			List<String> look = new ArrayList<>(List.of(java.toString(), "tools/Lens.java", "look", dir.toString()));
			for (String png : pngs) {
				String name = Path.of(png).getFileName().toString();
				look.add(name.substring(0, name.length() - 4));
			}

			run(look);
			if (pngs.size() > 1) {
				Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(reply);
				Path sheet = dir.getParent().resolve("lens").resolve((name.find() ? name.group(1) : "sheet") + ".png");
				List<String> tile = new ArrayList<>(List.of(java.toString(), "tools/Lens.java", "sheet", sheet.toString()));
				tile.addAll(pngs);
				run(tile);
			}
		}

		Matcher trace = Pattern.compile("\"trace\"\\s*:\\s*\"([^\"]+\\.jsonl)\"").matcher(reply);
		if (trace.find() && Files.exists(Path.of("tools", "HerdMap.java"))) {
			run(List.of(java.toString(), "tools/HerdMap.java", trace.group(1)));
		}
	}

	private static void run(List<String> command) throws Exception {
		new ProcessBuilder(command).inheritIO().start().waitFor();
	}

	// ---- swap ----

	private static void swap() throws Exception {
		String about = session();
		int port = Integer.parseInt(field(about, "debugPort"));
		if (port <= 0) {
			System.err.println("the session has no debugger port");
			System.exit(3);
		}

		Path stamp = WORK.resolve("stamp");
		FileTime since = Files.exists(stamp) ? Files.getLastModifiedTime(stamp) : Files.getLastModifiedTime(SESSION);
		FileTime now = FileTime.fromMillis(System.currentTimeMillis());
		String classpath = field(about, "classpath").replace("\\\\", "\\");
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) {
			System.err.println("this Java has no compiler: run the tool with a JDK's java");
			System.exit(3);
		}

		List<Path[]> compiled = new ArrayList<>();
		for (String set : SOURCE_SETS) {
			Path root = Path.of("src", set, "java");
			if (!Files.isDirectory(root)) {
				continue;
			}

			List<String> changed;
			try (Stream<Path> files = Files.walk(root)) {
				changed = files.filter(f -> f.toString().endsWith(".java")).filter(f -> modified(f).compareTo(since) > 0).map(Path::toString).toList();
			}

			if (changed.isEmpty()) {
				continue;
			}

			Path out = WORK.resolve(set);
			clean(out);
			Files.createDirectories(out);
			List<String> options = new ArrayList<>(List.of("-d", out.toString(), "-cp", WORK.resolve("main") + java.io.File.pathSeparator + classpath,
					"-proc:none", "-encoding", "UTF-8", "-nowarn", "-g"));
			options.addAll(changed);
			System.out.println("compiling " + changed.size() + " changed file(s) in " + root);
			if (javac.run(null, null, null, options.toArray(String[]::new)) != 0) {
				System.err.println("did not compile: nothing was swapped");
				System.exit(1);
			}

			try (Stream<Path> files = Files.walk(out)) {
				files.filter(f -> f.toString().endsWith(".class")).forEach(f -> compiled.add(new Path[]{f, out.relativize(f), Path.of("build", "classes", "java", set)}));
			}
		}

		if (compiled.isEmpty()) {
			System.out.println("nothing changed since " + since);
			return;
		}

		AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
				.filter(c -> c.transport().name().equals("dt_socket")).findFirst().orElseThrow();
		Map<String, Connector.Argument> arguments = connector.defaultArguments();
		arguments.get("hostname").setValue("127.0.0.1");
		arguments.get("port").setValue(String.valueOf(port));
		VirtualMachine vm = connector.attach(arguments);
		int swapped = 0;
		int restart = 0;
		try {
			for (Path[] c : compiled) {
				String name = c[1].toString().replace(java.io.File.separatorChar, '.').replaceFirst("\\.class$", "");
				byte[] code = Files.readAllBytes(c[0]);
				// Later loads (and classes not loaded yet) take the new code from the classes folder.
				Path target = c[2].resolve(c[1]);
				Files.createDirectories(target.getParent());
				Files.copy(c[0], target, StandardCopyOption.REPLACE_EXISTING);
				if (name.contains(".mixin.")) {
					System.out.println("  restart  " + name + " (a mixin is woven in at load)");
					restart++;
					continue;
				}

				List<ReferenceType> loaded = vm.classesByName(name);
				if (loaded.isEmpty()) {
					System.out.println("  later    " + name + " (not loaded yet: it will load the new code)");
					continue;
				}

				try {
					for (ReferenceType type : loaded) {
						vm.redefineClasses(Map.of(type, code));
					}

					System.out.println("  swapped  " + name);
					swapped++;
				} catch (RuntimeException | LinkageError e) {
					System.out.println("  restart  " + name + " (" + e.getMessage() + ")");
					restart++;
				}
			}
		} finally {
			vm.dispose();
		}

		Files.createDirectories(WORK);
		if (!Files.exists(stamp)) {
			Files.createFile(stamp);
		}

		Files.setLastModifiedTime(stamp, now);
		System.out.println(swapped + " swapped, " + restart + " need a restart" + (restart > 0 ? ": java tools/Live.java quit, then start the session again" : ""));
	}

	private static FileTime modified(Path f) {
		try {
			return Files.getLastModifiedTime(f);
		} catch (IOException e) {
			return FileTime.fromMillis(0);
		}
	}

	private static void clean(Path dir) throws IOException {
		if (Files.exists(dir)) {
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(f);
				}
			}
		}
	}
}
