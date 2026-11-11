package dev.fluidgore.dismember;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.mixin.EntityRenderDispatcherAccessor;
import dev.fluidgore.mixin.HumanoidArmorLayerAccessor;
import dev.fluidgore.mixin.LivingEntityRendererAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * The armour a severed part was wearing, copied off the body's armour layer so it goes with the part: the helmet on
 * the head, the chestplate's sleeve on an arm, the leggings and boots on a leg. Each layer is drawn the way vanilla's
 * {@code EquipmentLayerRenderer} draws it (dye colour, glint), minus trims.
 */
public final class ArmorPieces {
	/** One layer of armour on a piece: the armour model's copy of the part, its texture and colour. */
	public record Piece(ModelPart model, Identifier texture, int color, boolean foil) {
	}

	private ArmorPieces() {
	}

	/** The armour {@code entity} wears over {@code bodyPart}, ready to draw on the severed copy; empty if none. */
	public static List<Piece> of(LivingEntity entity, ModelParts.Posed posed, BodyPart bodyPart) {
		List<Piece> pieces = new ArrayList<>();
		if (!GoreConfig.get().armorOnParts) {
			return pieces;
		}

		ArmorModelSet<?> models = null;
		for (RenderLayer<?, ?> layer : ((LivingEntityRendererAccessor) posed.renderer()).fluidgore$getLayers()) {
			if (layer instanceof HumanoidArmorLayer<?, ?, ?> armor) {
				HumanoidArmorLayerAccessor access = (HumanoidArmorLayerAccessor) armor;
				models = posed.state().isBaby ? access.fluidgore$getBabyModelSet() : access.fluidgore$getModelSet();
				break;
			}
		}

		if (models == null) {
			return pieces;
		}

		for (EquipmentSlot slot : slots(bodyPart)) {
			ItemStack stack = entity.getItemBySlot(slot);
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if (equippable == null || equippable.slot() != slot || equippable.assetId().isEmpty() || !(models.get(slot) instanceof Model<?> model)) {
				continue;
			}

			ModelPart source = ModelParts.index(model)[bodyPart.ordinal()];
			if (source == null) {
				continue;
			}

			EquipmentClientInfo.LayerType type = posed.state().isBaby ? EquipmentClientInfo.LayerType.HUMANOID_BABY
					: slot == EquipmentSlot.LEGS ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS : EquipmentClientInfo.LayerType.HUMANOID;
			EquipmentClientInfo info = ((EntityRenderDispatcherAccessor) Minecraft.getInstance().getEntityRenderDispatcher())
					.fluidgore$getEquipmentAssets().get(equippable.assetId().get());
			int dye = DyedItemColor.getOrDefault(stack, 0);
			boolean foil = stack.hasFoil();
			ModelPart copy = null;
			for (EquipmentClientInfo.Layer layer : info.getLayers(type)) {
				int color = color(layer, dye);
				if (color == 0 || layer.usePlayerTexture()) {
					continue;
				}

				// Every layer is the same shape, so they share one copy of the part.
				copy = copy != null ? copy : ModelParts.copy(source);
				pieces.add(new Piece(copy, layer.getTextureLocation(type), color, foil));
				foil = false;
			}
		}

		return pieces;
	}

	private static EquipmentSlot[] slots(BodyPart part) {
		return switch (part) {
			case HEAD -> new EquipmentSlot[]{EquipmentSlot.HEAD};
			case RIGHT_ARM, LEFT_ARM -> new EquipmentSlot[]{EquipmentSlot.CHEST};
			case RIGHT_LEG, LEFT_LEG -> new EquipmentSlot[]{EquipmentSlot.LEGS, EquipmentSlot.FEET};
		};
	}

	/** Vanilla's layer colour: white, the dye (or the undyed colour) for a dyeable layer, 0 to skip the layer. */
	private static int color(EquipmentClientInfo.Layer layer, int dye) {
		Optional<EquipmentClientInfo.Dyeable> dyeable = layer.dyeable();
		if (dyeable.isEmpty()) {
			return -1;
		}

		return dye != 0 ? dye : dyeable.get().colorWhenUndyed().map(ARGB::opaque).orElse(0);
	}

private static final Identifier SHEEP_WOOL = Identifier.withDefaultNamespace("textures/entity/sheep/sheep_wool.png");
private static final Identifier SHEEP_BABY_WOOL = Identifier.withDefaultNamespace("textures/entity/sheep/sheep_wool_baby.png");
private static final Identifier SHEEP_UNDERCOAT = Identifier.withDefaultNamespace("textures/entity/sheep/sheep_wool_undercoat.png");
/** The game's wool models, baked once from the model set they were baked from (a resource reload brings a new set). */
private static net.minecraft.client.model.geom.@Nullable EntityModelSet woolFrom;
private static final Model<?>[] WOOL = new Model<?>[3];

/**
 * A sheep's wool over {@code bodyPart}, ready to draw on the severed copy. The game draws a sheep in layers: the
 * body itself, an undercoat in the sheep's colour on any sheep that is not white, and the fleece in that colour on
 * one that has not been sheared. A part cut off takes its share of each with it, in the sheep's own colour: a brown
 * sheep's head comes off brown. A fleece that fire has charred away is gone from the part too.
 */
public static List<Piece> wool(ModelParts.Posed posed, BodyPart bodyPart) {
	List<Piece> pieces = new ArrayList<>();
	if (!(posed.state() instanceof net.minecraft.client.renderer.entity.state.SheepRenderState sheep) || sheep.isInvisible) {
		return pieces;
	}

	net.minecraft.client.model.geom.EntityModelSet models = Minecraft.getInstance().getEntityModels();
	if (models != woolFrom) {
		woolFrom = models;
		WOOL[0] = new net.minecraft.client.model.animal.sheep.SheepFurModel(models.bakeLayer(net.minecraft.client.model.geom.ModelLayers.SHEEP_WOOL_UNDERCOAT));
		WOOL[1] = new net.minecraft.client.model.animal.sheep.SheepFurModel(models.bakeLayer(net.minecraft.client.model.geom.ModelLayers.SHEEP_WOOL));
		WOOL[2] = new net.minecraft.client.model.animal.sheep.SheepFurModel(models.bakeLayer(net.minecraft.client.model.geom.ModelLayers.SHEEP_BABY_WOOL));
	}

	int color = ARGB.opaque(sheep.getWoolColor());
	if ((sheep.isJebSheep || sheep.woolColor != net.minecraft.world.item.DyeColor.WHITE) && !sheep.isBaby) {
		layer(pieces, WOOL[0], bodyPart, SHEEP_UNDERCOAT, color);
	}

	dev.fluidgore.wound.Cremation.Charred charred = sheep.getData(dev.fluidgore.wound.Cremation.KEY);
	if (!sheep.isSheared && (charred == null || !charred.shorn())) {
		layer(pieces, WOOL[sheep.isBaby ? 2 : 1], bodyPart, sheep.isBaby ? SHEEP_BABY_WOOL : SHEEP_WOOL, color);
	}

	return pieces;
}

private static void layer(List<Piece> pieces, Model<?> model, BodyPart bodyPart, Identifier texture, int color) {
	ModelPart source = ModelParts.index(model)[bodyPart.ordinal()];
	if (source != null) {
		pieces.add(new Piece(ModelParts.copy(source), texture, color, false));
	}
}

/** True if the piece has armour to draw. */
	public static boolean any(@Nullable List<Piece> pieces) {
		return pieces != null && !pieces.isEmpty();
	}
}
