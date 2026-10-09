package dev.fluidgore.trigger;

import dev.fluidgore.config.GoreConfig;
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

/** Decides which entities bleed, what colour, and which held items count as blades. */
public final class EntityFilter {
	private static List<String> cachedBladeSource;
	private static final Set<Identifier> bladeItems = new HashSet<>();
	private static final List<TagKey<Item>> bladeTags = new ArrayList<>();
	private static List<String> cachedBloodlessSource;
	private static final Set<Identifier> bloodless = new HashSet<>();

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

	public static int bloodColor(LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		return entity instanceof Enemy ? config.monsterBloodColor.rgb : config.bloodColor.rgb;
	}

	public static boolean isBloodless(LivingEntity entity) {
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
		if (source != cachedBladeSource) {
			cachedBladeSource = source;
			bladeItems.clear();
			bladeTags.clear();
			for (String raw : source) {
				String entry = raw.trim();
				boolean tag = entry.startsWith("#");
				Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
				if (id == null) {
					continue;
				}

				if (tag) {
					bladeTags.add(TagKey.create(Registries.ITEM, id));
				} else {
					bladeItems.add(id);
				}
			}
		}

		if (bladeItems.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
			return true;
		}

		for (TagKey<Item> tag : bladeTags) {
			if (stack.is(tag)) {
				return true;
			}
		}

		return false;
	}
}
