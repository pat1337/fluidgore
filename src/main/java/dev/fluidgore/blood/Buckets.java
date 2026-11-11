package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.server.BloodBuckets;
import dev.fluidgore.server.BloodyTools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Blood by the bucket. Use an empty bucket on standing blood and it takes up what lies within reach of its rim, by the
 * litre; use a bucket that holds blood anywhere else and it is tipped out there, to run where blood runs. Into water
 * it clouds. A bucket takes what the game's buckets take, a whole block of liquid, a thousand litres, so one is filled
 * from a pit, a trough or a cauldron under a bled carcass, not from a film on a floor. Sneak to use the bucket as
 * usual.
 *
 * <p>When the server runs Fluid Gore the bucket itself holds the blood and shows it ({@link BloodBuckets}), and the
 * others see the scooping and the pouring ({@link GoreSync}). Otherwise this game keeps its own reckoning of what the
 * player's bucket holds, which nobody else sees and the item does not show.
 */
public final class Buckets {
	private static final double REACH = 4.5;
	/** How far round the point it is dipped a bucket gathers blood from, blocks. */
	private static final double DIP = 0.6;
	/** The film, mm, a bucket cannot get off a floor. */
	private static final float FILM = 0.3F;
	/** Least worth dipping for, litres. */
	private static final float LEAST = 0.05F;
	private static final int RED = 0x8A0303;

	/** What this player's bucket holds, on a server that does not keep it. */
	private static float localLitres;
	private static int localRgb = RED;

	private Buckets() {
	}

	/** Litres of blood in the bucket {@code stack}, as far as this game knows. */
	public static float litres(ItemStack stack) {
		return GoreSync.serverKeepsBodies() ? BloodBuckets.litres(stack) : localLitres;
	}

	private static int colour(ItemStack stack) {
		return GoreSync.serverKeepsBodies() ? BloodBuckets.colour(stack, RED) : localRgb;
	}

	/**
	 * Use key pressed. Returns true if the click scooped or poured blood (the vanilla item use is then skipped): an
	 * empty bucket on water, or any bucket on a place with no blood to take and none in it to pour, is the game's own.
	 */
	public static boolean onUse(Minecraft mc, BloodWorld blood) {
		LocalPlayer player = mc.player;
		GoreConfig config = GoreConfig.get();
		if (!config.bloodBuckets || !config.goreLevel.enabled() || player == null || player.isShiftKeyDown()
				|| !(mc.level instanceof ClientLevel level) || !BloodBuckets.isBucket(player.getMainHandItem())) {
			return false;
		}

		ItemStack bucket = player.getMainHandItem();
		float held = litres(bucket);
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
		BlockHitResult wet = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
		if (wet.getType() == HitResult.Type.BLOCK && level.getFluidState(wet.getBlockPos()).is(FluidTags.WATER)) {
			if (held <= 0.0F) {
				// An empty bucket and water: the game fills it.
				return false;
			}

			// Blood goes into the water first; the next click takes up water.
			return tip(player, bucket, wet.getLocation(), held);
		}

		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.getLocation().distanceToSqr(eye) > REACH * REACH) {
			return false;
		}

		Vec3 spot = floor(level, hit);
		if (spot == null) {
			return false;
		}

		float room = BloodBuckets.capacity() - held;
		float there = blood.fluid().within(spot.x, spot.y, spot.z, DIP, FILM);
		if (room > 0.0F && there >= LEAST) {
			float litres = Math.min(room, there);
			BloodCell cell = blood.fluid().at(spot.x, spot.y, spot.z);
			int rgb = cell != null ? cell.rgb : RED;
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			if (GoreSync.act(GoreAction.of(GoreAction.SCOOP, -1, rgb, spot, Vec3.ZERO, 0, litres))) {
				if (!GoreSync.serverKeepsBodies()) {
					localRgb = localLitres <= 0.0F ? rgb : BloodyTools.mix(localRgb, localLitres, rgb, litres);
					localLitres += litres;
				}

				tell(player, held + litres);
			}

			return true;
		}

		return held > 0.0F && tip(player, bucket, spot, held);
	}

	/** Tips the bucket out at {@code at}. */
	private static boolean tip(LocalPlayer player, ItemStack bucket, Vec3 at, float held) {
		int rgb = colour(bucket);
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		if (GoreSync.act(GoreAction.of(GoreAction.POUR, -1, rgb, at, Vec3.ZERO, 0, held))) {
			localLitres = 0.0F;
			tell(player, 0.0F);
		}

		return true;
	}

	/** Says over the hotbar what the bucket holds now. */
	private static void tell(LocalPlayer player, float litres) {
		if (litres <= 0.0F) {
			player.sendOverlayMessage(Component.translatable("fluidgore.bucket.empty"));
		} else {
			player.sendOverlayMessage(Component.translatable("fluidgore.bucket.holds", BloodBuckets.amount(litres),
					BloodBuckets.amount(BloodBuckets.gallons(litres)), BloodBuckets.amount(BloodBuckets.capacity())));
		}
	}

	/** The floor a click on a block means: the top face clicked, or the floor at the foot of a side clicked. */
	private static @Nullable Vec3 floor(ClientLevel level, BlockHitResult hit) {
		Vec3 at = hit.getLocation();
		if (hit.getDirection() == Direction.UP) {
			return at;
		}

		if (hit.getDirection() == Direction.DOWN) {
			return null;
		}

		double x = at.x + hit.getDirection().getStepX() * 0.3;
		double z = at.z + hit.getDirection().getStepZ() * 0.3;
		double surface = SurfaceProbe.surfaceBelow(level, x, at.y + 0.1, at.y - 3.0, z);
		return Double.isNaN(surface) ? null : new Vec3(x, surface, z);
	}

	/** A bucket dipped at {@code at} takes up {@code litres}: played for the player who did it and for everyone shown it. */
	public static boolean scoop(ClientLevel level, BloodWorld blood, Vec3 at, float litres) {
		float taken = blood.fluid().take(at.x, at.y, at.z, DIP, litres, FILM);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8F, 0.75F + level.getRandom().nextFloat() * 0.1F, false);
		return taken > 0.0F || litres > 0.0F;
	}

	/** {@code litres} of {@code rgb} blood tipped out at {@code at}: onto the floor in a stream, or into water in a cloud. */
	public static boolean pour(ClientLevel level, BloodWorld blood, Vec3 at, float litres, int rgb) {
		if (!(litres > 0.0F)) {
			return false;
		}

		level.playLocalSound(at.x, at.y, at.z, SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.9F, 0.7F + level.getRandom().nextFloat() * 0.1F, false);
		if (WaterClouds.inWater(level, at.x, at.y - 0.05, at.z) || WaterClouds.inWater(level, at.x, at.y + 0.05, at.z)) {
			// A cloud holds only so much; a bucketful goes in as several, down through the water.
			float units = litres / BloodSupply.LITRES_PER_UNIT;
			int clouds = Math.clamp(Math.round(litres / 10.0F), 1, 8);
			for (int i = 0; i < clouds; i++) {
				blood.clouds().add(level, at.x, at.y - 0.1 - i * 0.25, at.z, units / clouds, rgb);
			}

			for (int i = 0; i < 6; i++) {
				level.addParticle(ParticleTypes.SPLASH, at.x, at.y + 0.1, at.z, 0.0, 0.1, 0.0);
			}

			return true;
		}

		blood.pours().start(at.x, at.y, at.z, litres, rgb);
		return true;
	}

	public static void clear() {
		localLitres = 0.0F;
		localRgb = RED;
	}
}
