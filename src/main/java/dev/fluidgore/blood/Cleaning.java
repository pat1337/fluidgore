package dev.fluidgore.blood;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.server.BloodyTools;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.trigger.EntityFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Cleaning up with a held cleaning item (a sponge or a brush by default): a body that is down is cleaned away, and
 * blood is scrubbed off surfaces. Holding the use key repeats the click, so a floor is wiped by dragging the crosshair
 * over it. A sponge is only kept from being placed while there is blood to wipe, or while the player is still scrubbing.
 * <p>
 * The tool takes up what it wipes (3.1, see {@link ToolSoak}): the bloodier it gets the less it lifts and the more it
 * drags along the stroke as smears, and guts on the floor are squashed into streaks. Soaked through, it leaves smears
 * wherever it is dragged. Washed in water, it cleans again.
 */
public final class Cleaning {
	private static final double REACH = 4.5;
	private static final float RADIUS = 0.85F;
	/** Ticks after a wipe during which clicks still count as scrubbing, so the last smear does not place the sponge. */
	private static final int SCRUB_GRACE = 12;
	/** Bloodier than this, the tool puts blood down wherever it goes. */
	private static final float DEPOSIT = 0.55F;
	/** Blood a squashed piece of gut leaves in the tool, litres. */
	private static final float GUT = 0.01F;
	/** Red when the tool says nothing about its blood. */
	private static final int RED = 0x8A0303;

	private static long lastScrub = Long.MIN_VALUE;
	private static @Nullable Vec3 lastAt;
	private static @Nullable Direction lastFace;

	private Cleaning() {
	}

	public static boolean isCleaningItem(ItemStack stack) {
		return BloodyTools.isTool(stack);
	}

	/**
	 * Use key pressed. Returns true if the click cleaned (the vanilla item use is then skipped). A bloody tool held to
	 * water is washed; on a body that is down it cleans the whole body away; on a surface it scrubs the blood there. All
	 * are shown to the other players when the server runs Fluid Gore (see {@link GoreSync}).
	 */
	public static boolean onUse(Minecraft mc, BloodWorld blood) {
		LocalPlayer player = mc.player;
		GoreConfig config = GoreConfig.get();
		if (!config.cleaning || !config.goreLevel.enabled() || player == null || player.isShiftKeyDown()
				|| !(mc.level instanceof ClientLevel level) || !isCleaningItem(player.getMainHandItem())) {
			return false;
		}

		ItemStack tool = player.getMainHandItem();
		float soak = ToolSoak.soak(tool);
		int toolRgb = ToolSoak.colour(tool, RED);
		if (soak > BloodyTools.CLEAN && BloodyTools.nearWater(player)) {
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			GoreSync.act(GoreAction.of(GoreAction.WASH, -1, toolRgb, water(player), Vec3.ZERO, 0, 0.0F));
			ToolSoak.rinse(tool);
			return true;
		}

		Corpse body = CorpseStrike.aimed(mc, player);
		if (body != null) {
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			ToolSoak.wipedBody(tool, EntityFilter.bloodType(body.entity).rgb);
			GoreSync.act(GoreAction.of(GoreAction.CLEAN, body.entity.getId(), 0L, player.getEyePosition(), player.getViewVector(1.0F), 0, 1.0F));
			return true;
		}

		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}

		Vec3 at = hit.getLocation();
		if (at.distanceToSqr(player.getEyePosition()) > REACH * REACH) {
			return false;
		}

		long now = level.getGameTime();
		Direction face = hit.getDirection();
		boolean scrubbing = now - lastScrub <= SCRUB_GRACE;
		if (level.getBlockState(hit.getBlockPos()).getBlock() instanceof BrushableBlock && !blood.hasBloodNear(at, RADIUS)) {
			// Suspicious sand or gravel: the brush is for digging there, bloody or not.
			return false;
		}

		Vec3 stroke = scrubbing && face == lastFace && lastAt != null ? stroke(at.subtract(lastAt)) : null;
		BloodWorld.Scrub result = scrub(level, blood, at, face, stroke, soak, toolRgb);
		if (result.touched() == 0 && result.smeared() == 0) {
			// Nothing here: only swallow the click while a scrub is under way, otherwise the sponge is placed as usual.
			return scrubbing && blood.hasBloodNear(at, RADIUS * 2.5F);
		}

		lastScrub = now;
		lastAt = at;
		lastFace = face;
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		ToolSoak.absorb(tool, result.lifted(), result.rgb());
		Vec3 sent = new Vec3(stroke != null ? stroke.x : 0.0, soak, stroke != null ? stroke.z : 0.0);
		GoreSync.send(GoreAction.of(GoreAction.SCRUB, -1, (long) toolRgb << 32 | result.rgb() & 0xFFFFFFFFL, at, sent, face.ordinal(), result.lifted()));
		return true;
	}

	/** The way a stroke went across the floor, unit length; null if the crosshair barely moved (a dab). */
	private static @Nullable Vec3 stroke(Vec3 moved) {
		Vec3 flat = new Vec3(moved.x, 0.0, moved.z);
		return flat.lengthSqr() > 0.03 * 0.03 ? flat.normalize() : null;
	}

	/** Where the tool goes into the water: where the player looks at it, or their feet if they stand in it. */
	private static Vec3 water(LocalPlayer player) {
		if (player.isInWater()) {
			return player.position();
		}

		Vec3 eye = player.getEyePosition();
		BlockHitResult hit = player.level().clip(new ClipContext(eye, eye.add(player.getViewVector(1.0F).scale(5.0)), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.WATER, player));
		return hit.getLocation();
	}

	/**
	 * One stroke of a tool {@code soak} bloody (its blood {@code toolRgb}) at {@code at} on {@code face}, going along
	 * {@code stroke} (null: a dab): blood taken up or smeared, guts squashed, and a soaked tool's own blood left behind,
	 * with the sound and wet of it.
	 */
	public static BloodWorld.Scrub scrub(ClientLevel level, BloodWorld blood, Vec3 at, Direction face, @Nullable Vec3 stroke, float soak, int toolRgb) {
		BloodWorld.Scrub wiped = blood.scrub(level, at, face, RADIUS, Math.max(0.05F, 1.0F - soak), stroke);
		int smeared = wiped.smeared();
		float lifted = wiped.lifted();
		int guts = 0;
		if (face == Direction.UP) {
			guts = GoreSystems.dismemberment().giblets().squash(level, blood, at, RADIUS, stroke);
			lifted += guts * GUT;
			smeared += guts;
			if (soak > DEPOSIT) {
				// Soaked through: what it holds comes back out as it is dragged.
				Vec3 way = stroke != null ? stroke : new Vec3(level.getRandom().nextDouble() - 0.5, 0.0, level.getRandom().nextDouble() - 0.5).normalize();
				if (blood.smear(level, at.x + way.x * 0.15, at.y, at.z + way.z * 0.15, way, 0.15F + 0.3F * soak, toolRgb)) {
					smeared++;
				}
			}
		}

		if (wiped.touched() == 0 && smeared == 0) {
			return wiped;
		}

		level.playLocalSound(at.x, at.y, at.z, smeared > 0 ? SoundEvents.HONEY_BLOCK_SLIDE : SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 0.7F,
				0.9F + level.getRandom().nextFloat() * 0.2F, false);
		for (int i = 0; i < 4; i++) {
			double ox = (level.getRandom().nextDouble() - 0.5) * RADIUS;
			double oz = (level.getRandom().nextDouble() - 0.5) * RADIUS;
			level.addParticle(ParticleTypes.SPLASH, at.x + (face.getStepX() == 0 ? ox : 0.0) + face.getStepX() * 0.05,
					at.y + (face.getStepY() == 0 ? Math.abs(oz) : 0.05), at.z + (face.getStepZ() == 0 ? oz : 0.0) + face.getStepZ() * 0.05,
					0.0, 0.05, 0.0);
		}

		return new BloodWorld.Scrub(wiped.touched(), lifted, wiped.touched() > 0 ? wiped.rgb() : toolRgb, smeared);
	}

	/** A bloody tool rinsed at {@code at}: the blood clouds out into the water. */
	public static void wash(ClientLevel level, Vec3 at, int toolRgb) {
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.45F, 1.2F + level.getRandom().nextFloat() * 0.2F, false);
		if (WaterClouds.inWater(level, at.x, at.y - 0.15, at.z)) {
			dev.fluidgore.GoreSystems.blood().clouds().add(level, at.x, at.y - 0.15, at.z, 0.04F, toolRgb);
		}

		DustParticleOptions cloud = new DustParticleOptions(toolRgb, 1.4F);
		for (int i = 0; i < 14; i++) {
			level.addParticle(cloud, at.x + (level.getRandom().nextDouble() - 0.5) * 0.6, at.y + level.getRandom().nextDouble() * 0.2,
					at.z + (level.getRandom().nextDouble() - 0.5) * 0.6, 0.0, 0.0, 0.0);
		}

		for (int i = 0; i < 5; i++) {
			level.addParticle(ParticleTypes.SPLASH, at.x, at.y + 0.1, at.z, 0.0, 0.1, 0.0);
		}
	}
}
