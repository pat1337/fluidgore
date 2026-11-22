package dev.fluidgore.test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.blood.BloodSupply;
import dev.fluidgore.blood.Pallor;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Suffering;
import dev.fluidgore.wound.WoundSet;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * What each screenshot shows, written beside it as {@code <picture>.json}: the camera, every body and loose piece in
 * the picture (where it is on the screen, whether a block hides it, its blood, wounds, burns and pallor) and the blood
 * in the world in litres. {@code tools/Lens.java} reads them back to cut each subject out at full size, mark the
 * picture, tile contact sheets and compare runs. Facts are only read here: nothing in this class changes the game, and
 * a frame whose facts cannot be read gets none rather than failing the test.
 */
final class Lens {
	/** Bodies and pieces further from the camera than this are left out. */
	private static final double REACH = 64.0;
	/** The most subjects listed for one picture, nearest first. */
	private static final int MOST = 40;

	private Lens() {
	}

	/** Screenshots are drawn at this many times the window's size ({@code -PshotScale=2}): more detail, same framing. */
	private static int scale = Math.max(1, Integer.getInteger("fluidgore.shotScale", 1));
	/** Screenshots leave out the hotbar, hearts and hand ({@code -PhideHud}). */
	private static boolean hideHud = Boolean.getBoolean("fluidgore.hideHud");

	/** {@code context}, with every screenshot it takes followed by the facts of that frame. */
	static ClientGameTestContext watch(ClientGameTestContext context) {
		return (ClientGameTestContext) Proxy.newProxyInstance(ClientGameTestContext.class.getClassLoader(), new Class<?>[]{ClientGameTestContext.class},
				(proxy, method, args) -> {
					boolean shot = method.getName().equals("takeScreenshot");
					if (shot && args[0] instanceof String name && (scale > 1 || hideHud)) {
						return take(context, name);
					}

					Object out;
					try {
						out = method.invoke(context, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}

					if (shot && out instanceof Path png) {
						write(context, png);
					}

					return out;
				});
	}

	/** A screenshot at {@link #scale} times the window's size, without the HUD if {@link #hideHud}, with its facts. */
	private static Path take(ClientGameTestContext context, String name) {
		int[] size = context.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
		boolean hid = context.computeOnClient(mc -> {
			boolean hide = hideHud && !mc.gui.hud.isHidden() && mc.gui.screen() == null;
			if (hide) {
				mc.gui.hud.toggle();
			}

			return hide;
		});
		try {
			Path png = context.takeScreenshot(TestScreenshotOptions.of(name).withSize(size[0] * scale, size[1] * scale));
			write(context, png);
			return png;
		} finally {
			if (hid) {
				context.runOnClient(mc -> mc.gui.hud.toggle());
			}
		}
	}

	/** From now on, screenshots at {@code n} times the window's size, with or without the HUD (the live session's {@code set}). */
	static void shots(int n, boolean hud) {
		scale = Math.clamp(n, 1, 4);
		hideHud = !hud;
	}

	static int scale() {
		return scale;
	}

	static boolean hud() {
		return !hideHud;
	}

	/** The facts of the frame on screen now, as written beside a screenshot, without taking one. */
	static String now(ClientGameTestContext context) {
		return context.computeOnClient(mc -> facts(mc, ""));
	}

	/** The facts file beside the screenshot {@code png}. */
	static Path factsOf(Path png) {
		String name = png.getFileName().toString();
		return png.resolveSibling(name.substring(0, name.length() - ".png".length()) + ".json");
	}

	/** The facts of the screenshot {@code png}. */
	static JsonObject read(Path png) throws IOException {
		return JsonParser.parseString(Files.readString(factsOf(png), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/**
	 * Four screenshots of {@code at} from the north, east, south and west, {@code distance} blocks out and {@code height}
	 * above it, named {@code <name>_n}, {@code _e}, {@code _s} and {@code _w}: {@code tools/Lens.java sheet} tiles them.
	 */
	static List<Path> orbit(ClientGameTestContext context, TestServerContext server, String name, Vec3 at, double distance, double height) {
		List<Path> shots = new ArrayList<>();
		String[] sides = {"n", "e", "s", "w"};
		double[][] ways = {{0.0, -1.0}, {1.0, 0.0}, {0.0, 1.0}, {-1.0, 0.0}};
		server.runCommand("gamemode spectator @a");
		for (int i = 0; i < 4; i++) {
			server.runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", at.x + ways[i][0] * distance, at.y + height - 1.62, at.z + ways[i][1] * distance));
			context.waitTicks(2);
			context.runOnClient(mc -> aim(mc, at));
			context.waitTicks(1);
			shots.add(context.takeScreenshot(name + "_" + sides[i]));
		}

		server.runCommand("gamemode creative @a");
		return shots;
	}

	/** Turns the player's view onto {@code at}, on the client: the game test's camera follows the client's turn. */
	static void aim(Minecraft mc, Vec3 at) {
		Vec3 d = at.subtract(mc.player.getEyePosition());
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
	}

	/** {@code count} screenshots {@code ticks} apart, named {@code <name>_f0}, {@code _f1} and so on: a filmstrip. */
	static List<Path> frames(ClientGameTestContext context, String name, int count, int ticks) {
		List<Path> shots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				context.waitTicks(ticks);
			}

			shots.add(context.takeScreenshot(name + "_f" + i));
		}

		return shots;
	}

	private static void write(ClientGameTestContext context, Path png) {
		try {
			String json = context.computeOnClient(mc -> facts(mc, png.getFileName().toString()));
			Files.writeString(factsOf(png), json, StandardCharsets.UTF_8);
		} catch (RuntimeException | IOException e) {
			System.out.println("[FluidGoreTest] lens: no facts for " + png.getFileName() + ": " + e);
		}
	}

	private static String facts(Minecraft mc, String shot) {
		JsonObject out = new JsonObject();
		out.addProperty("shot", shot);
		out.add("window", numbers(mc.getWindow().getWidth(), mc.getWindow().getHeight()));
		out.addProperty("goreLevel", GoreConfig.get().goreLevel.name());
		if (mc.gui.screen() != null) {
			out.addProperty("screen", mc.gui.screen().getClass().getSimpleName());
		}

		if (mc.level == null) {
			return new GsonBuilder().setPrettyPrinting().create().toJson(out);
		}

		try {
			Camera camera = mc.gameRenderer.mainCamera();
			Vec3 eye = camera.position();
			out.addProperty("gameTime", mc.level.getGameTime());
			JsonObject cam = new JsonObject();
			cam.add("pos", numbers(eye.x, eye.y, eye.z));
			cam.addProperty("yaw", round(camera.yRot()));
			cam.addProperty("pitch", round(camera.xRot()));
			cam.addProperty("fov", round(camera.getFov()));
			out.add("camera", cam);

			List<JsonObject> subjects = new ArrayList<>();
			Set<Integer> listed = new HashSet<>();
			float bodiesLost = 0.0F;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof LivingEntity living && e != mc.player && listed.add(e.getId())) {
					bodiesLost += BloodSupply.lost(e.getId());
					body(mc, camera, living, subjects);
				}
			}

			for (Corpse c : GoreSystems.suffering().corpses()) {
				if (listed.add(c.entity.getId())) {
					bodiesLost += BloodSupply.lost(c.entity.getId());
					body(mc, camera, c.entity, subjects);
				}
			}

			for (GorePart p : GoreSystems.dismemberment().parts().parts()) {
				piece(mc, camera, p, subjects);
			}

			out.add("blood", blood(mc, camera, bodiesLost));
			var giblets = GoreSystems.dismemberment().giblets();
			int gibletsSeen = 0;
			for (int i = 0; i < giblets.count(); i++) {
				if (onScreen(mc, camera, giblets.position(i)) != null) {
					gibletsSeen++;
				}
			}

			JsonObject gib = new JsonObject();
			gib.addProperty("count", giblets.count());
			gib.addProperty("inView", gibletsSeen);
			out.add("giblets", gib);

			subjects.sort(Comparator.comparingDouble(s -> s.get("dist").getAsDouble()));
			JsonArray list = new JsonArray();
			for (int i = 0; i < Math.min(MOST, subjects.size()); i++) {
				JsonObject s = subjects.get(i);
				s.addProperty("n", i + 1);
				list.add(s);
			}

			out.addProperty("subjectsInView", subjects.size());
			out.add("subjects", list);
		} catch (RuntimeException e) {
			out.addProperty("error", e.toString());
		}

		return new GsonBuilder().setPrettyPrinting().create().toJson(out);
	}

	private static void body(Minecraft mc, Camera camera, LivingEntity living, List<JsonObject> subjects) {
		int id = living.getId();
		Corpse corpse = GoreSystems.suffering().corpse(id);
		AABB box;
		if (corpse != null) {
			// A body in its throes or lying is drawn round its ragdoll, not its standing box: a cube that holds it lying any way.
			Vec3 c = Suffering.centre(corpse);
			double r = Math.max(living.getBbWidth(), living.getBbHeight()) * 0.5;
			double h = living.getBbWidth() * 0.5;
			box = new AABB(c.x - r, c.y - h, c.z - r, c.x + r, c.y + h, c.z + r);
		} else {
			box = living.getBoundingBox();
		}

		JsonObject s = placed(mc, camera, box);
		if (s == null) {
			return;
		}

		s.addProperty("kind", "body");
		s.addProperty("type", type(living.getType()));
		s.addProperty("id", id);
		s.addProperty("alive", living.isAlive());
		s.add("health", numbers(living.getHealth(), living.getMaxHealth()));
		if (living.isBaby()) {
			s.addProperty("baby", true);
		}

		JsonObject blood = new JsonObject();
		blood.addProperty("holdsL", round(BloodSupply.litres(living)));
		blood.addProperty("lostL", round(BloodSupply.lost(id)));
		blood.addProperty("lostShare", round(BloodSupply.lostShare(id)));
		s.add("blood", blood);
		s.addProperty("pallor", round(Pallor.of(living)));
		s.addProperty("char", round(GoreSystems.dismemberment().burns().charOf(id)));
		int mask = GoreSystems.dismemberment().missingMask(id);
		if (mask != 0) {
			JsonArray missing = new JsonArray();
			for (BodyPart part : BodyPart.values()) {
				if ((mask & part.bit()) != 0) {
					missing.add(part.name());
				}
			}

			s.add("missing", missing);
		}

		if (GoreSystems.dismemberment().vaporized(id)) {
			s.addProperty("vaporized", true);
		}

		WoundSet set = GoreSystems.dismemberment().wounds().get(id);
		if (set != null && set.count() > 0) {
			JsonObject wounds = new JsonObject();
			wounds.addProperty("count", set.count());
			wounds.addProperty("deepest", round(set.deepest()));
			wounds.addProperty("bleeding", round(set.bleeding()));
			wounds.addProperty("runs", set.runCount());
			wounds.addProperty("throatCut", set.throatCut());
			wounds.addProperty("lungPierced", set.lungPierced());
			JsonArray each = new JsonArray();
			for (int i = 0; i < set.count(); i++) {
				var organ = set.reached(i);
				each.add(String.format(Locale.ROOT, "%.2f/%s", set.severity(i), organ == null ? "wall" : organ.name()));
			}

			wounds.add("each", each);
			s.add("wounds", wounds);
		}

		if (corpse != null) {
			JsonObject c = new JsonObject();
			c.addProperty("kind", corpse.kind.name());
			c.addProperty("age", corpse.age);
			c.addProperty("held", corpse.held);
			c.addProperty("rig", corpse.rig != null);
			c.addProperty("roll", round(corpse.body.roll));
			s.add("corpse", c);
		}

		subjects.add(s);
	}

	private static void piece(Minecraft mc, Camera camera, GorePart p, List<JsonObject> subjects) {
		double r = Math.max(0.05, p.halfExtents.length() * p.scale);
		JsonObject s = placed(mc, camera, new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r));
		if (s == null) {
			return;
		}

		s.addProperty("kind", "piece");
		s.addProperty("key", Long.toHexString(p.key));
		if (p.label != null) {
			s.addProperty("label", p.label.getString());
		}

		if (p.organ != null) {
			s.addProperty("organ", p.organ + (p.side.isEmpty() ? "" : " " + p.side));
		}

		if (p.tissue != null) {
			s.addProperty("tissue", p.tissue.name());
		}

		if (p.owner != null) {
			s.addProperty("owner", type(p.owner));
		}

		s.add("size", numbers(p.halfExtents.x * p.scale * 2.0F, p.halfExtents.y * p.scale * 2.0F, p.halfExtents.z * p.scale * 2.0F));
		s.addProperty("age", p.age);
		s.addProperty("resting", p.resting);
		if (p.held) {
			s.addProperty("held", true);
		}

		if (p.rot > 0.0F) {
			s.addProperty("rot", round(p.rot));
		}

		if (p.wounds != null) {
			s.addProperty("rawFaces", p.rawFaces);
		}

		subjects.add(s);
	}

	/** Blood in the world in litres, and how much of it the picture shows. */
	private static JsonObject blood(Minecraft mc, Camera camera, float bodiesLost) {
		float liquid = 0.0F;
		float soaked = 0.0F;
		float liquidSeen = 0.0F;
		float soakedSeen = 0.0F;
		int cells = 0;
		int cellsSeen = 0;
		double[] box = {1.0, 1.0, 0.0, 0.0};
		for (BloodCell cell : GoreSystems.blood().fluid().cells()) {
			cells++;
			liquid += cell.litres;
			soaked += cell.soaked;
			double[] at = onScreen(mc, camera, new Vec3(cell.bx + 0.5, cell.surfaceY, cell.bz + 0.5));
			if (at != null) {
				cellsSeen++;
				liquidSeen += cell.litres;
				soakedSeen += cell.soaked;
				box[0] = Math.min(box[0], at[0]);
				box[1] = Math.min(box[1], at[1]);
				box[2] = Math.max(box[2], at[0]);
				box[3] = Math.max(box[3], at[1]);
			}
		}

		JsonObject out = new JsonObject();
		out.addProperty("bodiesLostL", round(bodiesLost));
		out.addProperty("groundLiquidL", round(liquid));
		out.addProperty("groundSoakedL", round(soaked));
		out.addProperty("cells", cells);
		out.addProperty("decals", GoreSystems.blood().decals().size());
		out.addProperty("droplets", GoreSystems.blood().droplets().droplets().size());
		out.addProperty("clouds", GoreSystems.blood().clouds().clouds().size());
		JsonObject seen = new JsonObject();
		seen.addProperty("cells", cellsSeen);
		seen.addProperty("liquidL", round(liquidSeen));
		seen.addProperty("soakedL", round(soakedSeen));
		if (cellsSeen > 0) {
			seen.add("box", numbers(box));
		}

		out.add("inView", seen);
		return out;
	}

	/**
	 * Where {@code box} lies on the screen, as a subject with its distance, its screen box (left, top, right, bottom, from
	 * 0 to 1) and whether a block stands between the eye and its middle; null when it is out of the picture or reach.
	 */
	private static JsonObject placed(Minecraft mc, Camera camera, AABB box) {
		Vec3 eye = camera.position();
		Vec3 centre = box.getCenter();
		double dist = eye.distanceTo(centre);
		if (dist > REACH) {
			return null;
		}

		double[] screen = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		boolean any = false;
		boolean behind = false;
		for (int i = 0; i < 8; i++) {
			double[] at = projected(mc, camera, new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ));
			if (at == null) {
				behind = true;
				continue;
			}

			any = true;
			screen[0] = Math.min(screen[0], at[0]);
			screen[1] = Math.min(screen[1], at[1]);
			screen[2] = Math.max(screen[2], at[0]);
			screen[3] = Math.max(screen[3], at[1]);
		}

		if (!any || screen[2] < 0.0 || screen[0] > 1.0 || screen[3] < 0.0 || screen[1] > 1.0) {
			return null;
		}

		if (behind) {
			// Partly behind the eye: it fills the picture on that side.
			screen = new double[]{0.0, 0.0, 1.0, 1.0};
		}

		for (int i = 0; i < 4; i++) {
			screen[i] = Math.clamp(screen[i], 0.0, 1.0);
		}

		HitResult hit = mc.level.clip(new ClipContext(eye, centre, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		JsonObject s = new JsonObject();
		s.addProperty("dist", round(dist));
		s.add("box", numbers(screen));
		s.addProperty("seen", hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(eye) >= dist - 0.6);
		s.add("at", numbers(centre.x, centre.y, centre.z));
		return s;
	}

	/** {@code at} on the screen from 0 to 1 (from the top left), or null when it is behind the eye or out of the picture. */
	private static double[] onScreen(Minecraft mc, Camera camera, Vec3 at) {
		double[] p = projected(mc, camera, at);
		return p == null || p[0] < 0.0 || p[0] > 1.0 || p[1] < 0.0 || p[1] > 1.0 ? null : p;
	}

	/** {@code at} on the plane of the screen from 0 to 1 (from the top left), or null when it is behind the eye. */
	private static double[] projected(Minecraft mc, Camera camera, Vec3 at) {
		Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
		if (at.subtract(camera.position()).dot(look) < 0.05) {
			return null;
		}

		Vec3 ndc = mc.gameRenderer.projectPointToScreen(at);
		return new double[]{(ndc.x + 1.0) * 0.5, (1.0 - ndc.y) * 0.5};
	}

	private static String type(EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}

	private static double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	private static JsonArray numbers(double... values) {
		JsonArray out = new JsonArray();
		for (double v : values) {
			out.add(round(v));
		}

		return out;
	}
}
