package dev.fluidgore.test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * A live session ({@code ./gradlew runClientGameTest -Plive}): the game test opens a flat creative world and keeps it,
 * frozen between commands, while {@code tools/Live.java} drives it from outside. Commands come over a socket on this
 * machine only, each with the session's random token; the port and token are in {@code live.json} in the test's game
 * folder. Every screenshot gets its facts ({@link Lens}). Nothing here runs unless {@code -Plive} is given.
 */
final class Live {
	private static final String HELP = """
			cmd <command>                 a server command as the player, at the player (then 2 ticks pass); prints what it said
			client <command>              a client command, such as "gore demo stuckpig" (then 2 ticks pass)
			tick [n]                      let n ticks pass (1 if not given); the world is frozen otherwise
			tp <x> <y> <z> [yaw pitch]    move the player (spectator while moved) and wait for the chunks to draw
			look <x> <y> <z>              turn the view onto a point
			where                         the camera, the game time and how much gore there is
			shot [name]                   a screenshot with its facts
			facts                         the facts of the frame on screen, without a screenshot
			orbit <x> <y> <z> [dist] [height] [name]   four screenshots round a point
			frames <name> <count> <ticks> a filmstrip
			hit <selector> <damage> [item]  hit with an item (iron sword if not given) as the player
			set scale <1-4> | set hud on|off   how screenshots are taken
			config [field] [value]        read or change a Fluid Gore setting for this session
			clear                         remove every mob and all gore
			quit                          end the session (the test then passes)""";
	private static final String[] SETUP = {"time set noon", "weather clear", "gamerule advance_time false", "gamerule spawn_mobs false",
			"gamerule send_command_feedback false", "gamemode creative @a", "tp @a 0.5 -60 0.5 0 20"};

	private final ClientGameTestContext context;
	private final TestServerContext server;
	private final Path folder = FabricLoader.getInstance().getGameDir();
	private int shots;
	private boolean open = true;

	private Live(ClientGameTestContext context, TestServerContext server) {
		this.context = context;
		this.server = server;
	}

	static void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			singleplayer.getConnection().waitForChunksRender();
			for (String command : SETUP) {
				singleplayer.getServer().runCommand(command);
			}

			context.waitTicks(20);
			new Live(context, singleplayer.getServer()).serve();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private void serve() throws IOException {
		byte[] secret = new byte[16];
		new SecureRandom().nextBytes(secret);
		String token = HexFormat.of().formatHex(secret);
		Path session = folder.resolve("live.json");
		try (ServerSocket socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
			socket.setSoTimeout(1000);
			JsonObject about = new JsonObject();
			about.addProperty("port", socket.getLocalPort());
			about.addProperty("token", token);
			about.addProperty("debugPort", Integer.getInteger("fluidgore.debugPort", -1));
			about.addProperty("pid", ProcessHandle.current().pid());
			about.addProperty("gameDir", folder.toAbsolutePath().toString());
			about.addProperty("classpath", System.getProperty("java.class.path"));
			Files.writeString(session, new GsonBuilder().setPrettyPrinting().create().toJson(about), StandardCharsets.UTF_8);
			System.out.println("[FluidGoreTest] live: listening on 127.0.0.1:" + socket.getLocalPort() + " (" + session + ")");
			while (open) {
				Socket client;
				try {
					client = socket.accept();
				} catch (SocketTimeoutException e) {
					continue;
				}

				try (client) {
					client.setSoTimeout(30_000);
					BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
					String line = in.readLine();
					JsonObject reply;
					if (line == null || !line.startsWith(token + " ")) {
						reply = failed("no command, or the wrong token");
					} else {
						reply = answer(line.substring(token.length() + 1).trim());
					}

					Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
					out.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(reply));
					out.write('\n');
					out.flush();
				} catch (IOException e) {
					System.out.println("[FluidGoreTest] live: a command was lost: " + e);
				}
			}
		} finally {
			Files.deleteIfExists(session);
		}

		System.out.println("[FluidGoreTest] live: session ended");
	}

	/** Carries out one command line; any failure is the answer, and the session goes on. */
	private JsonObject answer(String line) {
		String[] words = line.split("\\s+");
		String rest = line.contains(" ") ? line.substring(line.indexOf(' ') + 1).trim() : "";
		try {
			JsonObject reply = switch (words[0]) {
				case "help" -> said(HELP.lines().toList());
				case "cmd" -> command(rest);
				case "client" -> {
					context.runOnClient(mc -> mc.player.connection.sendCommand(rest));
					context.waitTicks(2);
					yield new JsonObject();
				}
				case "tick" -> {
					context.waitTicks(words.length > 1 ? Integer.parseInt(words[1]) : 1);
					yield where();
				}
				case "tp" -> tp(words);
				case "look" -> {
					Vec3 at = point(words, 1);
					context.runOnClient(mc -> Lens.aim(mc, at));
					context.waitTicks(1);
					yield where();
				}
				case "where" -> where();
				case "shot" -> {
					Path png = context.takeScreenshot(words.length > 1 ? words[1] : String.format(Locale.ROOT, "live_%03d", shots));
					shots++;
					JsonObject r = new JsonObject();
					r.addProperty("png", png.toAbsolutePath().toString());
					r.addProperty("facts", Lens.factsOf(png).toAbsolutePath().toString());
					yield r;
				}
				case "facts" -> JsonParser.parseString(Lens.now(context)).getAsJsonObject();
				case "orbit" -> {
					Vec3 at = point(words, 1);
					double distance = words.length > 4 ? Double.parseDouble(words[4]) : 3.0;
					double height = words.length > 5 ? Double.parseDouble(words[5]) : 1.5;
					String name = words.length > 6 ? words[6] : String.format(Locale.ROOT, "live_orbit_%03d", shots++);
					yield pictures(name, Lens.orbit(context, server, name, at, distance, height));
				}
				case "frames" -> pictures(words[1], Lens.frames(context, words[1], Integer.parseInt(words[2]), Integer.parseInt(words[3])));
				case "hit" -> {
					String item = words.length > 3 ? words[3] : "minecraft:iron_sword";
					server.runCommand("item replace entity @p weapon.mainhand with " + item);
					server.runCommand("execute as @p run damage " + words[1] + " " + words[2] + " minecraft:player_attack by @s");
					context.waitTicks(2);
					yield new JsonObject();
				}
				case "set" -> {
					if (words[1].equals("scale")) {
						Lens.shots(Integer.parseInt(words[2]), Lens.hud());
					} else if (words[1].equals("hud")) {
						Lens.shots(Lens.scale(), words[2].equals("on"));
					} else {
						throw new IllegalArgumentException("set scale <1-4> or set hud on|off");
					}

					JsonObject r = new JsonObject();
					r.addProperty("scale", Lens.scale());
					r.addProperty("hud", Lens.hud());
					yield r;
				}
				case "config" -> config(words);
				case "clear" -> {
					server.runCommand("kill @e[type=!minecraft:player]");
					context.waitTicks(2);
					context.runOnClient(mc -> GoreSystems.clearAll());
					yield where();
				}
				case "quit" -> {
					open = false;
					yield new JsonObject();
				}
				default -> throw new IllegalArgumentException("no command " + words[0] + "; try help");
			};
			reply.addProperty("ok", true);
			return reply;
		} catch (Throwable e) {
			// A failed command (a bad number, a check that timed out, an exception in the game) must not end the session.
			return failed(e.toString());
		}
	}

	/** Runs {@code command} on the server as the player, at the player, and returns what it said. */
	private JsonObject command(String command) {
		List<String> said = server.computeOnServer(s -> {
			List<String> out = new ArrayList<>();
			CommandSource listener = new CommandSource() {
				@Override
				public void sendSystemMessage(Component message) {
					out.add(message.getString());
				}

				@Override
				public boolean acceptsSuccess() {
					return true;
				}

				@Override
				public boolean acceptsFailure() {
					return true;
				}

				@Override
				public boolean shouldInformAdmins() {
					return false;
				}
			};
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSource(listener), "execute as @p at @p run " + command);
			return out;
		});
		context.waitTicks(2);
		return said(said);
	}

	private JsonObject tp(String[] words) {
		Vec3 at = point(words, 1);
		String turn = words.length > 5 ? " " + words[4] + " " + words[5] : "";
		server.runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", at.x, at.y, at.z) + turn);
		context.waitTicks(3);
		JsonObject r = where();
		try {
			context.waitFor(mc -> mc.levelRenderer.hasRenderedAllSections(), 200);
		} catch (AssertionError e) {
			r.addProperty("note", "not every chunk was drawn within 200 ticks");
		}

		return r;
	}

	/** Where the camera is and looks, the game time, and how much gore there is. */
	private JsonObject where() {
		return context.computeOnClient(mc -> {
			JsonObject r = new JsonObject();
			var camera = mc.gameRenderer.mainCamera();
			JsonArray pos = new JsonArray();
			pos.add(Math.round(camera.position().x * 100.0) / 100.0);
			pos.add(Math.round(camera.position().y * 100.0) / 100.0);
			pos.add(Math.round(camera.position().z * 100.0) / 100.0);
			r.add("camera", pos);
			r.addProperty("yaw", Math.round(camera.yRot() * 10.0F) / 10.0F);
			r.addProperty("pitch", Math.round(camera.xRot() * 10.0F) / 10.0F);
			r.addProperty("gameTime", mc.level.getGameTime());
			r.addProperty("bodies", GoreSystems.suffering().corpses().size());
			r.addProperty("pieces", GoreSystems.dismemberment().parts().parts().size());
			r.addProperty("giblets", GoreSystems.dismemberment().giblets().count());
			r.addProperty("bloodCells", GoreSystems.blood().fluid().size());
			r.addProperty("marks", GoreSystems.blood().decals().size());
			return r;
		});
	}

	/** Reads every setting, one setting, or sets one (then repairs it the way a loaded file is repaired). */
	private JsonObject config(String[] words) throws ReflectiveOperationException {
		JsonObject r = new JsonObject();
		GoreConfig config = context.computeOnClient(mc -> GoreConfig.get());
		if (words.length == 1) {
			for (Field f : GoreConfig.class.getFields()) {
				if (!Modifier.isStatic(f.getModifiers())) {
					r.addProperty(f.getName(), String.valueOf(f.get(config)));
				}
			}

			return r;
		}

		Field f = GoreConfig.class.getField(words[1]);
		if (words.length > 2) {
			Object value = parse(f.getType(), words[2]);
			context.runOnClient(mc -> {
				f.set(config, value);
				Method sanitize = GoreConfig.class.getDeclaredMethod("sanitize");
				sanitize.setAccessible(true);
				sanitize.invoke(config);
			});
		}

		r.addProperty(f.getName(), String.valueOf(f.get(config)));
		return r;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object parse(Class<?> type, String text) {
		if (type == boolean.class) {
			return Boolean.parseBoolean(text);
		} else if (type == int.class) {
			return Integer.parseInt(text);
		} else if (type == float.class) {
			return Float.parseFloat(text);
		} else if (type == double.class) {
			return Double.parseDouble(text);
		} else if (type == long.class) {
			return Long.parseLong(text);
		} else if (type.isEnum()) {
			for (Object constant : type.getEnumConstants()) {
				if (((Enum) constant).name().equalsIgnoreCase(text)) {
					return constant;
				}
			}

			throw new IllegalArgumentException(text + " is not one of " + java.util.Arrays.toString(type.getEnumConstants()));
		} else if (type == String.class) {
			return text;
		}

		throw new IllegalArgumentException("a " + type.getSimpleName() + " setting cannot be set from here");
	}

	private static Vec3 point(String[] words, int from) {
		return new Vec3(Double.parseDouble(words[from]), Double.parseDouble(words[from + 1]), Double.parseDouble(words[from + 2]));
	}

	private static JsonObject pictures(String name, List<Path> pngs) {
		JsonObject r = new JsonObject();
		r.addProperty("name", name);
		JsonArray list = new JsonArray();
		pngs.forEach(p -> list.add(p.toAbsolutePath().toString()));
		r.add("pngs", list);
		return r;
	}

	private static JsonObject said(List<String> lines) {
		JsonObject r = new JsonObject();
		JsonArray list = new JsonArray();
		lines.forEach(list::add);
		r.add("said", list);
		return r;
	}

	private static JsonObject failed(String why) {
		JsonObject r = new JsonObject();
		r.addProperty("ok", false);
		r.addProperty("error", why);
		return r;
	}
}
