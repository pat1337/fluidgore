package dev.fluidgore.suffer;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Builds screams out of vanilla animal sounds. A scream is a schedule of short layers: the animal's hurt sound
 * pushed to the top of the pitch range, an octave-down copy under it, and a low growl from its idle sound, re-
 * triggered a few ticks apart with the pitch bending. Played this way the familiar "moo" turns into something that
 * sounds like the animal is in agony. All sounds are local to this client.
 *
 * <p>Concurrent layers are capped by {@link GoreConfig#maxScreams}. Background sounds (whimpers, herd bellows,
 * gurgles) are dropped first; pain and death screams get a little headroom above the cap.
 */
public final class ScreamManager {
	private static final int LOW = 0;
	private static final int HIGH = 1;

	private record Clip(int playAt, int priority, SoundEvent sound, Supplier<Vec3> position, float startPitch, float endPitch,
			float wobble, float wobbleRate, float volume, float fade, int life) {
	}

	private final List<Clip> scheduled = new ArrayList<>();
	private final List<ScreamSound> playing = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private int tick;
	private int totalPlayed;

	/** Number of scream layers started since launch (used by the automated test). */
	public int totalPlayed() {
		return totalPlayed;
	}

	public int playingCount() {
		return playing.size();
	}

	/** A sharp cry when hit. Higher {@code fear} pushes it higher and longer. */
	public void pain(Supplier<Vec3> at, Voice voice, float intensity, float fear) {
		float top = 1.7F + 0.25F * Math.min(1.0F, fear + intensity * 0.2F);
		clip(voice.self(), 0, HIGH, voice.hurt(), at, top, top + 0.15F, 0.06F, 1.3F, 1.0F, 0.3F, 12);
		clip(voice.self(), 1, HIGH, voice.hurt(), at, top * 0.62F, top * 0.6F, 0.04F, 1.1F, 0.75F, 0.4F, 12);
		clip(voice.self(), 2, LOW, voice.ambient(), at, 0.62F, 0.55F, 0.05F, 0.9F, 0.5F, 0.6F, 16);
		int repeats = Math.round(intensity * (0.5F + voice.self().frantic()));
		for (int i = 0; i < repeats; i++) {
			int delay = 6 + i * 7 + random.nextInt(3);
			clip(voice.self(), delay, HIGH, voice.hurt(), at, top + 0.05F, top - 0.35F, 0.1F, 1.6F, 0.85F - i * 0.15F, 0.5F, 14);
		}
	}

	/**
	 * Death scream. {@code length} 0..1: a quick kill gets a single cut-off cry, a drawn-out death gets a long
	 * wail of repeated cries that sag in pitch and strength as the animal gives out.
	 */
	public void death(Supplier<Vec3> at, Voice voice, float intensity, float length) {
		int cries = 1 + Math.round(length * 6.0F * Math.min(1.5F, intensity) * voice.self().endurance());
		int delay = 0;
		for (int i = 0; i < cries; i++) {
			float progress = cries == 1 ? 0.0F : i / (float) (cries - 1);
			float pitch = 1.95F - progress * 0.95F;
			float volume = 1.25F - progress * 0.65F;
			clip(voice.self(), delay, HIGH, voice.hurt(), at, pitch, pitch - 0.25F, 0.08F + progress * 0.08F, 1.4F, volume, 0.5F, 16);
			clip(voice.self(), delay + 1, HIGH, voice.hurt(), at, pitch * 0.55F, pitch * 0.5F, 0.05F, 1.0F, volume * 0.7F, 0.5F, 16);
			delay += 5 + random.nextInt(4) + Math.round(progress * 5.0F);
		}

		clip(voice.self(), delay, HIGH, voice.death(), at, 0.75F, 0.6F, 0.04F, 0.7F, 0.9F, 0.7F, 30);
	}

	/** Wet, low, failing sounds from a dying animal. */
	public void gurgle(Supplier<Vec3> at, Voice voice, float strength) {
		clip(voice.self(), 0, LOW, voice.hurt(), at, 0.55F + random.nextFloat() * 0.08F, 0.5F, 0.06F, 2.2F, 0.25F + strength * 0.35F, 0.8F, 18);
	}

	/** Quiet, shaky crying of a badly wounded animal. */
	public void whimper(Supplier<Vec3> at, Voice voice) {
		float pitch = 1.55F + random.nextFloat() * 0.25F;
		clip(voice.self(), 0, LOW, voice.ambient(), at, pitch, pitch - 0.2F, 0.09F, 0.9F, 0.35F, 0.6F, 20);
	}

	/** A panicked call from an animal watching others being slaughtered. */
	public void bellow(Supplier<Vec3> at, Voice voice, float fear) {
		float pitch = 1.4F + fear * 0.5F + random.nextFloat() * 0.1F;
		float volume = 0.55F + fear * 0.45F;
		clip(voice.self(), 0, LOW, voice.ambient(), at, pitch, pitch + 0.1F, 0.12F, 1.2F, volume, 0.3F, 20);
		if (fear > 0.6F && random.nextBoolean()) {
			clip(voice.self(), 7 + random.nextInt(5), LOW, voice.ambient(), at, pitch + 0.1F, pitch - 0.3F, 0.15F, 1.5F, volume * 0.8F, 0.5F, 20);
		}
	}

	/** The weak last sounds of a severed head. */
	public void moan(Supplier<Vec3> at, Voice voice, float strength) {
		clip(voice.self(), 0, LOW, voice.ambient(), at, 0.5F + random.nextFloat() * 0.1F, 0.5F, 0.05F, 0.6F, 0.2F + 0.35F * strength, 0.8F, 24);
	}

	/** The slow, laboured breathing of an animal that is going down and knows it. */
	public void breath(Supplier<Vec3> at, Voice voice, float strength) {
		clip(voice.self(), 0, LOW, voice.ambient(), at, 0.58F, 0.5F, 0.03F, 0.5F, 0.18F + 0.22F * strength, 0.9F, 26);
	}

	/** The last breath: one long, low sigh that sinks away. */
	public void lastBreath(Supplier<Vec3> at, Voice voice) {
		clip(voice.self(), 0, HIGH, voice.death(), at, 0.62F, 0.5F, 0.02F, 0.4F, 0.55F, 0.9F, 44);
	}

	/** A mother calling for her young, or a young animal calling for its mother. */
	public void call(Supplier<Vec3> at, Voice voice, boolean young, float urgency) {
		call(at, voice, young, urgency, 0);
	}

	/** {@link #call} starting {@code delay} ticks from now: an answer, a moment after the cry it answers. */
	public void call(Supplier<Vec3> at, Voice voice, boolean young, float urgency, int delay) {
		float pitch = (young ? 1.75F : 0.95F) + random.nextFloat() * 0.1F;
		float volume = 0.6F + 0.4F * urgency;
		int priority = urgency >= 0.9F ? HIGH : LOW;
		clip(voice.self(), delay, priority, voice.ambient(), at, pitch, pitch + 0.15F, 0.08F, 1.0F, volume, 0.3F, 20);
		clip(voice.self(), delay + 9 + random.nextInt(4), priority, voice.ambient(), at, pitch + 0.1F, pitch - 0.2F, 0.1F, 1.3F, volume * 0.85F, 0.5F, 22);
	}

	/**
	 * A dying young animal still calling for its mother: thin, shaking and quieter each time, the pitch sinking as it
	 * runs out of breath. {@code strength} 1 = first calls, 0 = the last.
	 */
	public void weakCall(Supplier<Vec3> at, Voice voice, float strength) {
		float pitch = 1.45F + 0.3F * strength + random.nextFloat() * 0.05F;
		float volume = 0.2F + 0.45F * strength;
		clip(voice.self(), 0, HIGH, voice.ambient(), at, pitch, pitch - 0.35F, 0.12F + 0.1F * (1.0F - strength), 1.8F, volume, 0.6F, 24);
	}

	/**
	 * A mother whose young was killed (or a young animal whose mother was): a long low call that breaks in the middle
	 * and trails off. {@code strength} fades over the grief, so the calls get quieter as well as rarer.
	 */
	public void grieve(Supplier<Vec3> at, Voice voice, float strength, boolean young) {
		float base = young ? 1.6F : 0.8F;
		float volume = 0.45F + 0.6F * strength;
		int priority = strength > 0.6F ? HIGH : LOW;
		clip(voice.self(), 0, priority, voice.ambient(), at, base, base - 0.08F, 0.05F, 0.7F, volume, 0.4F, 32);
		// The crack: a higher, broken note, then sinking.
		clip(voice.self(), 14 + random.nextInt(4), priority, voice.ambient(), at, base * 1.3F, base * 0.85F, 0.16F, 2.2F, volume * 0.8F, 0.6F, 28);
		clip(voice.self(), 34 + random.nextInt(6), priority, voice.ambient(), at, base * 0.9F, base * 0.72F, 0.06F, 0.6F, volume * 0.55F, 0.8F, 34);
	}

	/** The drone of flies over a body that has been lying a while. */
	public void flies(Supplier<Vec3> at, float density) {
		clip(Personality.NEUTRAL, 0, LOW, SoundEvents.BEE_LOOP, at, 1.7F, 1.5F, 0.08F, 0.6F, 0.06F + 0.1F * density, 0.5F, 60);
	}

	private void clip(Personality self, int delay, int priority, SoundEvent sound, Supplier<Vec3> position, float startPitch, float endPitch,
			float wobble, float wobbleRate, float volume, float fade, int life) {
		scheduled.add(new Clip(tick + delay, priority, sound, position, Mth.clamp(startPitch * self.pitch(), 0.5F, 2.0F),
				Mth.clamp(endPitch * self.pitch(), 0.5F, 2.0F), wobble, wobbleRate, volume * self.loudness(), fade, life));
	}

	public void tick() {
		tick++;
		var sounds = Minecraft.getInstance().getSoundManager();
		playing.removeIf(s -> s.isStopped() || !sounds.isActive(s));

		GoreConfig config = GoreConfig.get();
		float master = config.screamVolume / 100.0F;
		Iterator<Clip> it = scheduled.iterator();
		while (it.hasNext()) {
			Clip clip = it.next();
			if (clip.playAt() > tick) {
				continue;
			}

			it.remove();
			int limit = clip.priority() == HIGH ? config.maxScreams + 4 : config.maxScreams;
			if (master <= 0.0F || playing.size() >= limit) {
				continue;
			}

			ScreamSound sound = new ScreamSound(clip.sound(), clip.position(), clip.startPitch(), clip.endPitch(), clip.wobble(),
					clip.wobbleRate(), clip.volume() * master, clip.fade(), clip.life(), random);
			sounds.play(sound);
			playing.add(sound);
			totalPlayed++;
		}
	}

	public void clear() {
		scheduled.clear();
		var sounds = Minecraft.getInstance().getSoundManager();
		for (ScreamSound sound : playing) {
			sounds.stop(sound);
		}

		playing.clear();
	}
}
