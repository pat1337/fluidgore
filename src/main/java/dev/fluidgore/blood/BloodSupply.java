package dev.fluidgore.blood;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.wound.Cremation;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * How much blood is really in each body, in litres, and how much of it has come out. Every drop that leaves a body
 * (sprays, spurts, oozing, drips, the pool under it) is drawn from here, so a chicken can never leave more than the
 * dessert-spoon or two a chicken really bleeds, and a cow empties a bucket and more onto the floor, then stops.
 * <ul>
 * <li>Blood volume: body weight ({@link Cremation#kilograms}) times the species' blood per kilogram (veterinary
 *     reference values: cattle 57 ml/kg, horse 76, pig 65, sheep 60, goat 70, chicken 70, dog 86, cat 55, people 70).</li>
 * <li>What can come out: a living body loses about a third before it dies of it (class IV haemorrhage, 35-40%), so
 *     that is as much as a survivor can bleed; vanilla decides when it dies. A dead body drains to about 60%, as a
 *     carcass does when it is bled at slaughter (the rest stays in the tissue). A body torn apart by a blast lets go of
 *     nine tenths. A walking corpse's blood has long clotted: only 15% of it runs.</li>
 * <li>Pressure: the flow falls off as the body empties, so the last of it trickles. A heart pumping into a cut artery
 *     is strong at first and fades in the minute or two a real animal takes to bleed out.</li>
 * </ul>
 * Pool units ({@link BloodCell#volume}) are tied to litres by {@link #LITRES_PER_UNIT}: blood on a hard floor settles
 * about 2.5 mm deep, so a puddle covering a whole block (0.6 units) holds about 2.4 litres. Bodies are found by entity id
 * while one is being bled ({@link #begin}); spurts and oozing remember the body they came from.
 */
public final class BloodSupply {
	/** Litres of blood per pool unit (see {@link BloodCell#FULL}: a full block of puddle, 2.5 mm deep, is 2.4 litres). */
	public static final float LITRES_PER_UNIT = 4.0F;
	/** Blood volume per kilogram of body, ml, by entity type path. */
	private static final Map<String, Float> ML_PER_KG = Map.ofEntries(
			Map.entry("cow", 57.0F), Map.entry("mooshroom", 57.0F), Map.entry("horse", 76.0F), Map.entry("donkey", 70.0F),
			Map.entry("mule", 72.0F), Map.entry("zombie_horse", 76.0F), Map.entry("skeleton_horse", 76.0F), Map.entry("camel", 65.0F),
			Map.entry("llama", 65.0F), Map.entry("trader_llama", 65.0F), Map.entry("pig", 65.0F), Map.entry("hoglin", 65.0F),
			Map.entry("sheep", 60.0F), Map.entry("goat", 70.0F), Map.entry("chicken", 70.0F), Map.entry("parrot", 80.0F),
			Map.entry("rabbit", 57.0F), Map.entry("wolf", 86.0F), Map.entry("fox", 80.0F), Map.entry("cat", 55.0F),
			Map.entry("ocelot", 60.0F), Map.entry("polar_bear", 70.0F), Map.entry("panda", 70.0F), Map.entry("dolphin", 70.0F),
			Map.entry("turtle", 50.0F), Map.entry("frog", 50.0F), Map.entry("axolotl", 50.0F), Map.entry("squid", 40.0F),
			Map.entry("spider", 30.0F), Map.entry("cave_spider", 30.0F), Map.entry("bat", 90.0F)
	);
	/** People and every other humanoid. */
	private static final float DEFAULT_ML_PER_KG = 70.0F;
	/** Share of the blood that is still in the body at the most each kind of body can lose. */
	private static final float LIVING_FLOOR = 0.65F;
	private static final float DEAD_FLOOR = 0.4F;
	private static final float SHREDDED_FLOOR = 0.1F;
	private static final float ROTTEN_FLOOR = 0.85F;

	/** One body's blood. */
	private static final class Reservoir {
		final LivingEntity body;
		final float litres;
		float lost;

		Reservoir(LivingEntity body, float litres) {
			this.body = body;
			this.litres = litres;
		}
	}

	private static final Int2ObjectOpenHashMap<Reservoir> RESERVOIRS = new Int2ObjectOpenHashMap<>();
	/** The body being bled right now, or null: blood made with none is not anyone's (a severed piece, a puddle). */
	private static @Nullable Reservoir current;

	private BloodSupply() {
	}

	/** Litres of blood in a whole, healthy {@code entity}. Override per mob with {@code bloodAmounts} (litres). */
	public static float litres(LivingEntity entity) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		Float custom = GoreConfig.get().bloodAmounts.get(id);
		if (custom != null) {
			return Math.max(0.0F, custom) * (entity.isBaby() ? 0.15F : 1.0F);
		}

		String path = id.substring(id.indexOf(':') + 1);
		return Cremation.kilograms(entity) * ML_PER_KG.getOrDefault(path, DEFAULT_ML_PER_KG) / 1000.0F;
	}

	/** From now until {@link #end}, blood that comes out is {@code body}'s. */
	public static void begin(@Nullable LivingEntity body) {
		current = body == null ? null : reservoir(body);
	}

	public static void end() {
		current = null;
	}

	/** The body being bled now, as an id to hand to something that bleeds later (a spurt), or -1. */
	public static int currentId() {
		return current == null ? -1 : current.body.getId();
	}

	/** Runs {@code bleed} as body {@code id}'s blood (see {@link #currentId}); the scope before is put back after. */
	public static void as(int id, Runnable bleed) {
		Reservoir before = current;
		current = id < 0 ? null : RESERVOIRS.get(id);
		try {
			bleed.run();
		} finally {
			current = before;
		}
	}

	private static Reservoir reservoir(LivingEntity body) {
		Reservoir r = RESERVOIRS.get(body.getId());
		if (r == null || r.body != body) {
			r = new Reservoir(body, litres(body));
			RESERVOIRS.put(body.getId(), r);
		}

		return r;
	}

	/**
	 * {@code units} of blood (pool units) want to come out of the body being bled: how much really does. The flow is
	 * as strong as the pressure left: full at first, falling to nothing as the body reaches what it can lose.
	 */
	public static float draw(float units) {
		Reservoir r = current;
		if (r == null || units <= 0.0F) {
			return units;
		}

		float floor = floor(r.body);
		float span = 1.0F - floor;
		float left = 1.0F - r.lost / Math.max(1.0E-4F, r.litres);
		float pressure = Mth.clamp((left - floor) / span, 0.0F, 1.0F);
		float wanted = units * LITRES_PER_UNIT * (float) Math.sqrt(pressure);
		float granted = Math.min(wanted, Math.max(0.0F, (left - floor) * r.litres));
		r.lost += granted;
		return granted / LITRES_PER_UNIT;
	}

	/** Share of its blood a body keeps at the most it can lose, as it is now. */
	private static float floor(LivingEntity body) {
		if (EntityFilter.isRotten(body)) {
			return ROTTEN_FLOOR;
		}

		if (GoreSystems.dismemberment().vaporized(body.getId())) {
			return SHREDDED_FLOOR;
		}

		return body.isAlive() ? LIVING_FLOOR : DEAD_FLOOR;
	}

	/** Litres {@code entityId} has lost so far (0 if it never bled). */
	public static float lost(int entityId) {
		Reservoir r = RESERVOIRS.get(entityId);
		return r == null ? 0.0F : r.lost;
	}

	/** {@code fraction} of the health came back: the body makes that share of what it lost again; all of it at full health. */
	public static void refill(int entityId, float fraction, boolean full) {
		Reservoir r = RESERVOIRS.get(entityId);
		if (r != null) {
			r.lost = full ? 0.0F : Math.max(0.0F, r.lost * (1.0F - fraction));
		}
	}

	public static void forget(int entityId) {
		Reservoir r = RESERVOIRS.get(entityId);
		// A body still lying in its death throes keeps bleeding from the same reservoir after it unloads.
		if (r != null && !GoreSystems.suffering().holdsBody(entityId)) {
			RESERVOIRS.remove(entityId);
		}
	}

	public static void clear() {
		RESERVOIRS.clear();
		current = null;
	}
}
