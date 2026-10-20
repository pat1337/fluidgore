package dev.fluidgore.trigger;

import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.config.BloodColor;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.wound.Flesh;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Decides which entities bleed, what colour, and which held items count as blades. */
public final class EntityFilter {
	/** The bladed-items list as last read. The server asks too (3.0, {@code HitReports}), from its own thread. */
	private record Blades(List<String> source, Set<Identifier> items, List<TagKey<Item>> tags) {
	}

	private static volatile @Nullable Blades blades;
	private static List<String> cachedBloodlessSource;
	private static final Set<Identifier> bloodless = new HashSet<>();
	private static List<String> cachedBoneSource;
	private static final Set<Identifier> bone = new HashSet<>();

	private EntityFilter() {
	}

	/** True if the settings allow effects on this entity at all. */
	public static boolean affects(LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled() || isBloodless(entity)) {
			return false;
		}

		if (entity instanceof Player) {
			return config.affectPlayers;
		}

		if (entity instanceof Enemy) {
			return config.affectMobs;
		}

		return config.affectAnimals;
	}

	/** Blood colour, or {@link BoneEmitter#BONE} for bony bodies, which break instead of bleeding. */
	public static int bloodColor(LivingEntity entity) {
		return isBone(entity) ? BoneEmitter.BONE : bloodType(entity).rgb;
	}

	/** True for skeletons and other bony bodies: they chip, crack and shatter, and never bleed. */
	public static boolean isBone(LivingEntity entity) {
		List<String> source = GoreConfig.get().boneEntities;
		if (source != cachedBoneSource) {
			cachedBoneSource = source;
			bone.clear();
			for (String entry : source) {
				Identifier id = Identifier.tryParse(entry.trim());
				if (id != null) {
					bone.add(id);
				}
			}
		}

		return bone.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	public static BloodColor bloodType(LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		if (isRotten(entity)) {
			return BloodColor.ROTTEN;
		}

		if (Flesh.ender(entity.getType())) {
			return BloodColor.ENDER;
		}

		return entity instanceof Enemy ? config.monsterBloodColor : config.bloodColor;
	}

	/** A rotting body (zombies and their kin, with Rotten Undead on): its blood is old, thick and slow. */
	public static boolean isRotten(LivingEntity entity) {
		return GoreConfig.get().rottenUndead && Flesh.rotten(entity.getType());
	}

	public static boolean isBloodless(LivingEntity entity) {
		if (isBone(entity)) {
			// Old configs list skeletons as bloodless; bone takes priority so they still break.
			return false;
		}

		List<String> source = GoreConfig.get().bloodlessEntities;
		if (source != cachedBloodlessSource) {
			cachedBloodlessSource = source;
			bloodless.clear();
			for (String entry : source) {
				Identifier id = Identifier.tryParse(entry.trim());
				if (id != null) {
					bloodless.add(id);
				}
			}
		}

		return bloodless.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	/** True if the stack is in the configured list of cutting items ({@code #tag} or item id entries). */
	public static boolean isBladed(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		List<String> source = GoreConfig.get().bladedItems;
		Blades current = blades;
		if (current == null || current.source() != source) {
			// Built aside and swapped in whole: the client and the server thread never see one half-made.
			Set<Identifier> items = new HashSet<>();
			List<TagKey<Item>> tags = new ArrayList<>();
			for (String raw : source) {
				String entry = raw.trim();
				boolean tag = entry.startsWith("#");
				Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
				if (id == null) {
					continue;
				}

				if (tag) {
					tags.add(TagKey.create(Registries.ITEM, id));
				} else {
					items.add(id);
				}
			}

			current = new Blades(source, Set.copyOf(items), List.copyOf(tags));
			blades = current;
		}

		if (current.items().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
			return true;
		}

		for (TagKey<Item> tag : current.tags()) {
			if (stack.is(tag)) {
				return true;
			}
		}

		return false;
	}
}
