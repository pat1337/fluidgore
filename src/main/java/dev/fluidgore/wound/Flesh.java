package dev.fluidgore.wound;

import dev.fluidgore.trigger.EntityFilter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * What a body is made of, which decides how each wound layer looks. Layers are bruise, gash, open meat and bone.
 * Ordinary flesh takes its colours from the creature's blood; skeletons scuff and crack; husks and camel husks are dried leather
 * that splits to show a sickly, poison-coloured ooze.
 */
public enum Flesh {
	MEAT,
	BONE,
	DRIED;

	private static final int POISON_OOZE = 0x6E8A3A;

	public static Flesh of(LivingEntity entity) {
		if (EntityFilter.isBone(entity)) {
			return BONE;
		}

		EntityType<?> type = entity.getType();
		return type == EntityTypes.HUSK || type == EntityTypes.CAMEL_HUSK ? DRIED : MEAT;
	}

	public boolean bleeds() {
		return this != BONE;
	}

	/** Opaque RGB tint of wound layer 0-3 on a creature whose blood is {@code blood}. */
	public int tint(int layer, int blood) {
		return switch (this) {
			case MEAT -> switch (layer) {
				case 0 -> mix(scale(blood, 0.5F), 0x3A1F3D, 0.55F);
				case 1 -> scale(blood, 0.8F);
				case 2 -> rgb(r(blood) * 0.7F + 80.0F, g(blood) * 0.55F + 38.0F, b(blood) * 0.55F + 42.0F);
				default -> 0xE6DDC8;
			};
			case BONE -> switch (layer) {
				case 0 -> 0x9A968C;
				case 1 -> 0x4A463F;
				case 2 -> 0xB8B09A;
				default -> 0xD8D2C0;
			};
			case DRIED -> switch (layer) {
				case 0 -> 0x5E5A3A;
				case 1 -> POISON_OOZE;
				case 2 -> 0x8A7A55;
				default -> 0xD8CFB0;
			};
		};
	}

	private static int r(int rgb) {
		return rgb >> 16 & 0xFF;
	}

	private static int g(int rgb) {
		return rgb >> 8 & 0xFF;
	}

	private static int b(int rgb) {
		return rgb & 0xFF;
	}

	private static int rgb(float r, float g, float b) {
		return clamp(r) << 16 | clamp(g) << 8 | clamp(b);
	}

	private static int clamp(float c) {
		return Math.max(0, Math.min(255, Math.round(c)));
	}

	private static int scale(int rgb, float f) {
		return rgb(r(rgb) * f, g(rgb) * f, b(rgb) * f);
	}

	private static int mix(int from, int to, float t) {
		return rgb(r(from) + (r(to) - r(from)) * t, g(from) + (g(to) - g(from)) * t, b(from) + (b(to) - b(from)) * t);
	}
}
