package dev.fluidgore.suffer;

import java.util.function.Supplier;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * One distorted layer of a scream: a vanilla animal sound whose pitch glides from {@code startPitch} to
 * {@code endPitch} with a vibrato wobble while it plays, and whose volume fades out. Several of these, stacked and
 * re-triggered, make a scream (see {@link ScreamManager}). The sound follows its source (a living animal, a
 * corpse or a severed head) as it moves.
 */
public final class ScreamSound extends AbstractTickableSoundInstance {
	private final Supplier<Vec3> position;
	private final float startPitch;
	private final float endPitch;
	private final float wobble;
	private final float wobbleRate;
	private final float startVolume;
	private final float fade;
	private final int life;
	private int age;

	public ScreamSound(SoundEvent event, Supplier<Vec3> position, float startPitch, float endPitch, float wobble, float wobbleRate,
			float volume, float fade, int life, RandomSource random) {
		super(event, SoundSource.NEUTRAL, random);
		this.position = position;
		this.startPitch = startPitch;
		this.endPitch = endPitch;
		this.wobble = wobble;
		this.wobbleRate = wobbleRate;
		this.startVolume = volume;
		this.fade = fade;
		this.life = Math.max(1, life);
		this.volume = volume;
		this.pitch = startPitch;
		Vec3 pos = position.get();
		this.x = pos.x;
		this.y = pos.y;
		this.z = pos.z;
	}

	@Override
	public void tick() {
		age++;
		if (age > life) {
			stop();
			return;
		}

		Vec3 pos = position.get();
		x = pos.x;
		y = pos.y;
		z = pos.z;
		float t = age / (float) life;
		pitch = Mth.lerp(t, startPitch, endPitch) + wobble * Mth.sin(age * wobbleRate);
		// Always end at silence: stopping a sound while it is still audible is a click.
		float tail = Mth.clamp((1.0F - t) / 0.25F, 0.0F, 1.0F);
		volume = startVolume * (1.0F - fade * t) * tail;
	}
}
