package dev.fluidgore.player;

import dev.fluidgore.GoreSystems;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The ringing in the ears after a blast: a high, thin tone in the head (not placed in the world), loudest at once and
 * fading as the hearing comes back. It plays on the master channel, the one channel deafness leaves alone (see
 * {@link PlayerInjuries#hearing}).
 */
final class Tinnitus extends AbstractTickableSoundInstance {
	private int age;

	Tinnitus() {
		super(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.MASTER, SoundInstance.createUnseededRandom());
		this.looping = true;
		this.relative = true;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.pitch = 2.0F;
		this.volume = GoreSystems.injuries().ringing();
	}

	@Override
	public void tick() {
		age++;
		volume = GoreSystems.injuries().ringing();
		if (volume <= 0.002F && age > 5) {
			stop();
		}
	}
}
