import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The jar gate as one command (run from the project folder): the full game-test suite, run again and again on the
 * commit checked out, each run held to the gate's pass checks (see "Docs and releases" in CONTRIBUTING.md).
 *
 * <pre>
 * java tools/Gate.java                       # ten full runs; stops at the first that fails
 * java tools/Gate.java 3                     # three runs
 * java tools/Gate.java 10 --port 25600       # the test's dedicated server on another port (-PserverPort)
 * java tools/Gate.java 10 --timeout 90      # stop a run that has gone on 90 minutes (120 by default) and fail it
 * java tools/Gate.java 10 -- -PcobbleTankDir=../tank   # anything after -- goes to Gradle
 * </pre>
 * Each run must have Gradle exit with 0, print its {@code [FluidGoreTest]} lines, have no {@code AssertionError}, and
 * reach the end of the dedicated-server half. The fifth check, looking at the screenshots of what changed, is by eye:
 * the last run's screenshots are kept in {@code build/gate/screenshots}. The working tree must be clean, so the runs
 * are of a commit; {@code --dirty} runs anyway, and the result then does not count. Logs and {@code gate.txt} (the
 * commit and each run's result) are written to {@code build/gate}.
 */
public class Gate {
	private static final Path OUT = Path.of("build", "gate");
	private static final Path SCREENSHOTS = Path.of("build", "run", "clientGameTest", "screenshots");
	/** Printed by the game test when the dedicated server's part of the full suite is over. */
	private static final String SERVER_DONE = "[FluidGoreTest] dedicated server passed";
	/** Gradle options that run less than the whole suite, or more than this project. */
	private static final String[] NOT_FULL = {"-PonlyTests", "-PonlyDemos", "-Plive", "-PextraMods", "-Pshaders"};

	public static void main(String[] args) throws Exception {
		int runs = 10;
		int timeout = 120;
		boolean dirty = false;
		List<String> gradleArgs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if ((args[i].equals("--port") || args[i].equals("--timeout")) && i + 1 == args.length) {
				usage();
			}

			switch (args[i]) {
				case "--port" -> gradleArgs.add("-PserverPort=" + Integer.parseInt(args[++i]));
				case "--timeout" -> timeout = Integer.parseInt(args[++i]);
				case "--dirty" -> dirty = true;
				case "--" -> {
					gradleArgs.addAll(List.of(args).subList(i + 1, args.length));
					i = args.length;
				}
				default -> {
					if (!args[i].matches("\\d+")) {
						usage();
					}

					runs = Integer.parseInt(args[i]);
				}
			}
		}

		for (String arg : gradleArgs) {
			for (String not : NOT_FULL) {
				if (arg.startsWith(not)) {
					System.err.println(not + " does not run the plain full suite, which is what the gate runs");
					System.exit(2);
				}
			}
		}

		if (!Files.isRegularFile(Path.of("build.gradle")) || !Files.isDirectory(Path.of("tools"))) {
			System.err.println("run this from the project folder: java tools/Gate.java");
			System.exit(2);
		}

		String commit = git(Path.of("."), "rev-parse", "HEAD");
		String changes = changes();
		if (!changes.isEmpty() && !dirty) {
			System.err.println("the working tree has changes, so the runs would not be of a commit (--dirty to run anyway):");
			System.err.println(changes);
			System.exit(2);
		}

		Path tank = Path.of(gradleArgs.stream().filter(a -> a.startsWith("-PcobbleTankDir=")).map(a -> a.substring(16)).findFirst().orElse("../CobbleTank"));
		String tankCommit = Files.isDirectory(tank.resolve(".git")) ? git(tank, "rev-parse", "--short", "HEAD") + (git(tank, "status", "--porcelain").isEmpty() ? "" : " with changes") : "not a git checkout";

		Files.createDirectories(OUT);
		List<String> report = new ArrayList<>();
		report.add("Jar gate, " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME.withLocale(Locale.ROOT)).substring(0, 16));
		report.add("Commit " + commit + (changes.isEmpty() ? "" : " WITH UNCOMMITTED CHANGES: does not count"));
		report.add("Cobble Tank (" + tank + ") " + tankCommit);
		report.add("Gradle: runClientGameTest " + String.join(" ", gradleArgs));
		report.add("");
		for (String line : report) {
			System.out.println(line);
		}

		int passed = 0;
		for (int run = 1; run <= runs; run++) {
			String again = git(Path.of("."), "rev-parse", "HEAD");
			if (!again.equals(commit) || (changes.isEmpty() && !changes().isEmpty())) {
				report.add("Stopped before run " + run + ": the checkout changed while the gate ran.");
				System.out.println(report.getLast());
				break;
			}

			Path log = OUT.resolve("run-%02d.log".formatted(run));
			System.out.print("Run " + run + " of " + runs + " ... ");
			System.out.flush();
			long start = System.nanoTime();
			Result result = run(gradleArgs, log, timeout);
			String took = minutes(System.nanoTime() - start);
			keepScreenshots();
			List<String> failed = result.failures();
			String line = "Run " + run + ": " + (failed.isEmpty() ? "passed the four checks" : "FAILED (" + String.join("; ", failed) + ")")
					+ ", " + result.lines + " [FluidGoreTest] lines, " + took + ", log " + log;
			report.add(line);
			System.out.println(failed.isEmpty() ? "passed, " + took : "FAILED, " + took);
			if (!failed.isEmpty()) {
				System.out.println("  " + String.join("\n  ", failed));
				for (String first : result.firstError) {
					System.out.println("  | " + first);
				}

				break;
			}

			passed++;
		}

		report.add("");
		boolean counts = passed == runs && changes.isEmpty();
		report.add(passed + " of " + runs + " runs passed" + (counts ? "." : passed == runs ? ", but on uncommitted changes: not a gate." : ". The count starts again after the fix."));
		report.add("Still to do by eye: look at the screenshots of what changed (the last run's are in " + OUT.resolve("screenshots")
				+ "; java tools/Lens.java diff <saved run> " + OUT.resolve("screenshots") + " lists what changed).");
		Files.write(OUT.resolve("gate.txt"), report, StandardCharsets.UTF_8);
		System.out.println();
		System.out.println(report.get(report.size() - 2));
		System.out.println(report.getLast());
		System.out.println("Written to " + OUT.resolve("gate.txt"));
		System.exit(counts ? 0 : 1);
	}

	private static void usage() {
		System.err.println("usage: java tools/Gate.java [runs] [--port <n>] [--timeout <minutes>] [--dirty] [-- <Gradle arguments>]");
		System.exit(2);
	}

	/** What one run of the suite showed. */
	private record Result(int exit, boolean timedOut, int lines, boolean assertion, boolean serverDone, List<String> firstError) {
		List<String> failures() {
			List<String> failed = new ArrayList<>();
			if (timedOut) {
				failed.add("timed out and was stopped");
			} else if (exit != 0) {
				failed.add("Gradle exited with " + exit);
			}

			if (lines == 0) {
				failed.add("no [FluidGoreTest] lines");
			}

			if (assertion) {
				failed.add("an AssertionError");
			}

			if (!serverDone) {
				failed.add("the dedicated-server half did not finish");
			}

			return failed;
		}
	}

	private static Result run(List<String> gradleArgs, Path log, int timeoutMinutes) throws IOException, InterruptedException {
		boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
		List<String> command = new ArrayList<>(windows ? List.of("cmd", "/c", "gradlew.bat") : List.of("./gradlew"));
		command.add("runClientGameTest");
		command.addAll(gradleArgs);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		int[] lines = {0};
		boolean[] assertion = {false};
		boolean[] serverDone = {false};
		List<String> firstError = new ArrayList<>();
		Thread reader = Thread.ofPlatform().start(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
					PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					out.println(line);
					if (line.contains("[FluidGoreTest]")) {
						lines[0]++;
					}

					if (line.contains(SERVER_DONE)) {
						serverDone[0] = true;
					}

					// The error and the test lines under it in its stack trace.
					if (line.contains("AssertionError")) {
						assertion[0] = true;
					}

					if (assertion[0] && firstError.size() < 6 && (firstError.isEmpty() ? line.contains("AssertionError") : line.contains("dev.fluidgore"))) {
						firstError.add(line.strip());
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		boolean done = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
		if (!done) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly().waitFor();
		}

		reader.join();
		return new Result(done ? process.exitValue() : -1, !done, lines[0], assertion[0], serverDone[0], firstError);
	}

	/** The run's screenshots, kept past the next run (which wipes them). */
	private static void keepScreenshots() throws IOException {
		Path kept = OUT.resolve("screenshots");
		if (Files.isDirectory(kept)) {
			try (Stream<Path> old = Files.walk(kept)) {
				for (Path p : old.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(p);
				}
			}
		}

		if (!Files.isDirectory(SCREENSHOTS)) {
			return;
		}

		try (Stream<Path> files = Files.walk(SCREENSHOTS)) {
			for (Path p : files.toList()) {
				Path to = kept.resolve(SCREENSHOTS.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(to);
				} else {
					Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Changes to tracked files, and new files under src/ (they are built too); other untracked files do not count. */
	private static String changes() throws IOException, InterruptedException {
		return git(Path.of("."), "status", "--porcelain").lines()
				.filter(line -> !line.startsWith("??") || line.substring(3).startsWith("src/"))
				.reduce((a, b) -> a + "\n" + b).orElse("");
	}

	private static String git(Path dir, String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		if (process.waitFor() != 0) {
			throw new IllegalStateException("git " + String.join(" ", args) + " in " + dir + ": " + out);
		}

		return out;
	}

	private static String minutes(long nanos) {
		Duration d = Duration.ofNanos(nanos);
		return d.toMinutes() + " min " + d.toSecondsPart() + " s";
	}
}
