package dev.fluidgore.server;

import dev.fluidgore.net.HitReport;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.Weapon;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The server's word on every hit (3.0, see {@link HitReport}): sent from {@code LivingEntityHurtMixin} once vanilla's
 * {@code hurtServer} has dealt it, to the players who can see the body (and to the body, if it is a player). Nothing is
 * worked out here but what only the server knows; the gore is still the clients'. A client on a server without this
 * keeps guessing from health drops, as before.
 */
public final class HitReports {
	private HitReports() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(HitReport.TYPE, HitReport.CODEC);
		// Never sent this way: listening on the channel is how a client tells this server reports hits (an older
		// Fluid Gore server keeps bodies but does not, and its players must keep guessing).
		PayloadTypeRegistry.serverboundPlay().register(HitReport.TYPE, HitReport.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HitReport.TYPE, (report, context) -> {
		});
	}

	/**
	 * {@code body} was hurt by {@code source}: its health went from {@code before} to what it is now. Only called when
	 * the hit landed (a shield that stopped it all, or invulnerability, sends nothing).
	 */
	public static void dealt(LivingEntity body, ServerLevel level, DamageSource source, float before) {
		Entity cause = source.getEntity();
		Entity direct = source.getDirectEntity();
		boolean melee = cause != null && direct == cause;
		boolean explosion = source.is(DamageTypeTags.IS_EXPLOSION);
		float after = body.getHealth();
		byte flags = (byte) ((melee ? HitReport.MELEE : 0) | (explosion ? HitReport.EXPLOSION : 0) | (source.is(DamageTypeTags.IS_FIRE) ? HitReport.FIRE : 0)
				| (source.is(DamageTypeTags.IS_FALL) ? HitReport.FALL : 0) | (body.isDeadOrDying() ? HitReport.LETHAL : 0));

		Vec3 rayFrom = null;
		Vec3 rayDir = null;
		if (!explosion && direct instanceof Projectile projectile && projectile.getDeltaMovement().lengthSqr() > 1.0E-6) {
			// Where the projectile flew in: it is still at the start of the step that hit.
			rayFrom = projectile.getBoundingBox().getCenter();
			rayDir = projectile.getDeltaMovement().normalize();
		} else if (melee && cause instanceof Player player) {
			// The player's real view, not the rotation everyone else is sent (rounded to 1.4 degrees, a tick late).
			rayFrom = player.getEyePosition();
			rayDir = player.getViewVector(1.0F);
		}

		HitReport report = new HitReport(body.getId(), cause != null ? cause.getId() : -1, flags, weapon(source, cause, melee, explosion),
				Math.max(0.0F, before - after), after, Fate.seed(body, after, cause), source.getSourcePosition(), rayFrom, rayDir);
		for (ServerPlayer player : PlayerLookup.tracking(body)) {
			send(player, report);
		}

		if (body instanceof ServerPlayer self) {
			send(self, report);
		}
	}

	private static void send(ServerPlayer player, HitReport report) {
		if (ServerPlayNetworking.canSend(player, HitReport.TYPE)) {
			ServerPlayNetworking.send(player, report);
		}
	}

	/** What the blow was, as the client would name it from the held item, but from the stack that really struck. */
	private static byte weapon(DamageSource source, @Nullable Entity cause, boolean melee, boolean explosion) {
		if (explosion) {
			return -1;
		}

		if (source.getDirectEntity() instanceof Projectile) {
			// A dispenser's arrow has no one behind it, but it pierces all the same.
			return (byte) Weapon.PIERCE.ordinal();
		}

		if (cause == null) {
			return -1;
		}

		if (melee && cause instanceof LivingEntity living) {
			ItemStack held = source.getWeaponItem() != null ? source.getWeaponItem() : living.getMainHandItem();
			if (held.isEmpty() && !(living instanceof Player) && !(living instanceof Zombie)) {
				return (byte) Weapon.BITE.ordinal();
			}

			return (byte) Weapon.of(held).ordinal();
		}

		return (byte) Weapon.PIERCE.ordinal();
	}
}
