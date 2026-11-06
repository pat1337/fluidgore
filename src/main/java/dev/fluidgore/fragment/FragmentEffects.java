package dev.fluidgore.fragment;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodSupply;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.event.Events;
import dev.fluidgore.net.Evidence;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.HitFeedback;
import dev.fluidgore.wound.Weapon;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Client projection and short-lived debris. Server box contacts never imply an exact internal-organ trajectory. */
public final class FragmentEffects {
	public static final int CAPACITY = 512;
	private static final List<Flight> FLIGHTS = new ArrayList<>();
	private static final LinkedHashSet<Long> RECEIVED = new LinkedHashSet<>();
	private static int tick;
	public static final class Flight {
		public final Vec3 origin;
		public final FragmentPath path;
		public final long seed, event;
		public final Events.Source source;
		public final int duration;
		public int age;
		private boolean handled;
		private Flight(Vec3 origin, FragmentPath path, long seed, long event, Events.Source source) {
			this.origin = origin; this.path = path; this.seed = seed; this.event = event; this.source = source;
			duration = Math.clamp((int) Math.ceil(origin.distanceTo(path.end()) / 4), 2, 12);
		}
		public Vec3 position(float partial) { return origin.lerp(path.end(), Math.clamp((age + partial) / duration, 0, 1)); }
		public float size(float partial) { return Math.clamp((duration + 6 - age - partial) / 6.0F, 0, 1); }
	}
	private FragmentEffects() {}
	public static List<Flight> flights() { return List.copyOf(FLIGHTS); }

	/** Remember the last 64 deliveries; identical seeded bursts with different delivery ids still both act. */
	public static boolean accept(Vec3 origin, long seed, long occurrence, long event, Events.Source source, List<FragmentPath> paths) {
		if (!RECEIVED.add(occurrence)) return false;
		if (RECEIVED.size() > 64) RECEIVED.removeFirst();
		for (FragmentPath path : paths.stream().limit(FragmentPath.MAX_PATHS).toList()) {
			if (!path.finite(origin)) continue;
			if (FLIGHTS.size() == CAPACITY) FLIGHTS.removeFirst();
			Flight flight = new Flight(origin, path, seed, event, source);
			FLIGHTS.add(flight);
			apply(flight);
		}
		return true;
	}

	public static void tick() {
		tick++;
		for (Flight flight : FLIGHTS) { flight.age++; if (!flight.handled && flight.age <= 10) apply(flight); }
		FLIGHTS.removeIf(f -> f.age > Math.max(10, f.duration + 6));
	}

	private static void apply(Flight flight) {
		var contact = flight.path.contact();
		if (contact == null || !GoreConfig.get().goreLevel.enabled()) { flight.handled = true; return; }
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(contact.entityId()) instanceof LivingEntity body) || !body.getUUID().equals(contact.uuid())) return;
		if (dev.fluidgore.dismember.ModelParts.pose(body) == null) return;
		flight.handled = true;
		Fate.begin(flight.seed ^ contact.uuid().getMostSignificantBits() ^ contact.uuid().getLeastSignificantBits() ^ flight.path.index());
		BloodSupply.begin(body);
		Events.begin(flight.event, flight.source);
		try {
			var strike = GoreSystems.dismemberment().wounds().fragment(body, contact);
			// This trace identifies the physical contact even when the render model has a gap at the proxy's surface.
			Events.record(Events.Channel.FRAGMENT, flight.path.index(), strike == null ? Events.Effect.IMPULSE : Events.Effect.WOUND, Events.body(body),
					String.format(java.util.Locale.ROOT, "contact local %.3f %.3f %.3f, force %.3f", contact.local().x, contact.local().y, contact.local().z, contact.damage()));
			if (strike != null) {
				Vec3 direction = FragmentTrace.turn(contact.direction(), body.yBodyRot);
				HitFeedback.hit(mc.level, GoreSystems.blood(), body, strike, Weapon.PIERCE, direction);
				if (strike.fracture() != dev.fluidgore.wound.Wounds.INTACT) HitFeedback.fracture(mc.level, body, strike);
				Evidence.touched(body.getId());
			}
		} finally { Events.end(); BloodSupply.end(); Fate.end(); }
	}

	public static void clear() { FLIGHTS.clear(); RECEIVED.clear(); tick = 0; }
}
