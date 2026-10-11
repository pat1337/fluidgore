package dev.fluidgore.wound;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.EntityFilter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * What a body is made of, which decides the colour of each tissue a wound opens down through. Ordinary flesh takes its
 * colours from the creature's blood; skeletons scuff and crack; husks and camel husks are dried leather that splits to
 * show a sickly, poison-coloured ooze; zombies are rotting meat.
 */
public enum Flesh {
	MEAT,
	/** Poultry: raw chicken, pale pink meat with yellow fat and a little blood in it. */
	POULTRY,
	/** Pork: pale pink lean under a thick slab of white back fat, the rind pink-grey. */
	PORK,
	/** Beef: deep red steak, marbled with creamy fat. */
	BEEF,
	/** An enderman: black hide over dark violet flesh, cut glowing magenta, its bones pale as end stone. */
	ENDER,
	BONE,
	DRIED,
	/**
	 * A walking corpse, as decomposition really goes: the skin round a wound marbles green-black and purple, the fat
	 * under it has turned to grey wax (adipocere), what is in the cut is black-brown clotted ooze, the muscle is
	 * grey-green and soft, there is pus, there are maggots, and the bone is stained yellow-brown.
	 */
	ROTTEN;

	/** The tissues a wound shows, from the skin down, plus the rot that lives in it, and bare skin where feathers were plucked. */
	public enum Tissue {
		BRUISE, FAT, SPLIT, MUSCLE, BONE, PUS, MAGGOTS, SKIN
	}

	private static final int POISON_OOZE = 0x6E8A3A;

	public static Flesh of(LivingEntity entity) {
		if (EntityFilter.isBone(entity)) {
			return BONE;
		}

		EntityType<?> type = entity.getType();
		if (type == EntityTypes.CHICKEN) {
			return POULTRY;
		}

		if (ender(type)) {
			return ENDER;
		}

		if (type == EntityTypes.PIG) {
			return PORK;
		}

		if (type == EntityTypes.COW || type == EntityTypes.MOOSHROOM) {
			return BEEF;
		}

		if (type == EntityTypes.HUSK || type == EntityTypes.CAMEL_HUSK) {
			return DRIED;
		}

		return rotten(type) && GoreConfig.get().rottenUndead ? ROTTEN : MEAT;
	}

	/** Endermen and endermites: things of the End, with its own flesh and blood. */
	public static boolean ender(EntityType<?> type) {
		return type == EntityTypes.ENDERMAN || type == EntityTypes.ENDERMITE;
	}

	/** The undead that are rotting meat (husks are dried out instead, and skeletons are bone). */
	public static boolean rotten(EntityType<?> type) {
		return type == EntityTypes.ZOMBIE || type == EntityTypes.ZOMBIE_VILLAGER || type == EntityTypes.DROWNED
				|| type == EntityTypes.ZOMBIFIED_PIGLIN || type == EntityTypes.ZOGLIN || type == EntityTypes.ZOMBIE_HORSE;
	}

	public boolean bleeds() {
		return this != BONE;
	}

	/** True if this flesh has pus and maggots in its wounds. */
	public boolean rots() {
		return this == ROTTEN;
	}

	/** Opaque RGB tint of {@code tissue} on a creature whose blood is {@code blood}. */
	public int tint(Tissue tissue, int blood) {
		return switch (this) {
			case MEAT -> switch (tissue) {
				case BRUISE -> mix(scale(blood, 0.5F), 0x3A1F3D, 0.55F);
				case FAT -> 0xE8D59A;
				case SPLIT -> scale(blood, 0.8F);
				case MUSCLE -> rgb(r(blood) * 0.7F + 80.0F, g(blood) * 0.55F + 38.0F, b(blood) * 0.55F + 42.0F);
				case BONE -> 0xE6DDC8;
				case PUS -> 0xC8C060;
				case MAGGOTS -> 0xF0EBD8;
				// Plucked poultry: pale, a little yellow, pink where the blood shows through.
				case SKIN -> 0xF2CDB6;
			};
			case ENDER -> switch (tissue) {
				case BRUISE -> 0x1C0A2A;
				case FAT -> 0x8A5AC0;
				case SPLIT -> 0xE040FF;
				case MUSCLE -> 0x4A1468;
				case BONE -> 0xE8E4B0;
				case PUS -> 0xC8C060;
				case MAGGOTS -> 0xF0EBD8;
				case SKIN -> 0x161616;
			};
			case PORK -> switch (tissue) {
				case BRUISE -> 0x7A3A55;
				case FAT -> 0xF8F0E6;
				case SPLIT -> 0xB8404E;
				case MUSCLE -> 0xE8999A;
				case BONE -> 0xEFE6D2;
				case PUS -> 0xC8C060;
				case MAGGOTS -> 0xF0EBD8;
				case SKIN -> 0xF0B8A8;
			};
			case BEEF -> switch (tissue) {
				case BRUISE -> 0x4A2030;
				case FAT -> 0xF6EAD0;
				case SPLIT -> 0x7E141C;
				case MUSCLE -> 0xC83440;
				case BONE -> 0xE6DDC8;
				case PUS -> 0xC8C060;
				case MAGGOTS -> 0xF0EBD8;
				case SKIN -> 0xE8C0A8;
			};
			case POULTRY -> switch (tissue) {
				case BRUISE -> 0x8A4A6A;
				case FAT -> 0xF4E2A8;
				case SPLIT -> 0xD8606E;
				case MUSCLE -> 0xF4A2AC;
				case BONE -> 0xEADFCB;
				case PUS -> 0xC8C060;
				case MAGGOTS -> 0xF0EBD8;
				case SKIN -> 0xF2CDB6;
			};
			case BONE -> switch (tissue) {
				case BRUISE -> 0x9A968C;
				case FAT, MUSCLE -> 0xB8B09A;
				case SPLIT -> 0x4A463F;
				case BONE, PUS, MAGGOTS, SKIN -> 0xD8D2C0;
			};
			case DRIED -> switch (tissue) {
				case BRUISE -> 0x5E5A3A;
				case FAT -> 0xA8986A;
				case SPLIT -> POISON_OOZE;
				case MUSCLE -> 0x8A7A55;
				case BONE -> 0xD8CFB0;
				case PUS -> 0x9AA040;
				case MAGGOTS -> 0xE8E0C8;
				case SKIN -> 0xB8A27A;
			};
			case ROTTEN -> switch (tissue) {
				case BRUISE -> 0x3B3D25;
				case FAT -> 0xB5B08C;
				case SPLIT -> 0x2A1A10;
				case MUSCLE -> 0x6B5E3A;
				case BONE -> 0xBFAE7A;
				case PUS -> 0x9FA63A;
				case MAGGOTS -> 0xEDE6CF;
				case SKIN -> 0x8C9470;
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
