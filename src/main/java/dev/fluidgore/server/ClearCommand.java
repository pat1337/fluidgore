package dev.fluidgore.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.fluidgore.net.GoreClear;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /fluidgore clear}: takes away the bodies and gore in an area, so a scene can be set up again (a command block
 * in a test world, an operator tidying a server). It needs operator rights, as {@code /kill} does.
 * <ul>
 * <li>{@code /fluidgore clear}: within {@link #RADIUS} blocks of where it runs.</li>
 * <li>{@code /fluidgore clear <radius>}: within that many blocks.</li>
 * <li>{@code /fluidgore clear <from> <to>}: the blocks between two corners, as {@code /fill} takes them.</li>
 * <li>{@code /fluidgore clear all}: the whole dimension.</li>
 * </ul>
 * The server removes the bodies it keeps there (no smoke: they are not cleaned, they were never there) and the records
 * of loose pieces lying there, and tells every player with the mod the box ({@link GoreClear}); each game takes away the
 * blood, pieces and bodies it shows inside it. Living animals are left alone.
 */
public final class ClearCommand {
	/** Blocks round where the command runs that a bare {@code /fluidgore clear} clears. */
	private static final int RADIUS = 16;
	private static final int MAX_RADIUS = 256;
	/** Beyond any world border: {@code all}. */
	private static final double WORLD = 3.0E7;

	private ClearCommand() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(GoreClear.TYPE, GoreClear.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(Commands.literal("fluidgore")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("clear")
						.executes(c -> clear(c.getSource(), around(c.getSource().getPosition(), RADIUS)))
						.then(Commands.literal("all").executes(c -> clear(c.getSource(), new AABB(-WORLD, -WORLD, -WORLD, WORLD, WORLD, WORLD))))
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
								.executes(c -> clear(c.getSource(), around(c.getSource().getPosition(), IntegerArgumentType.getInteger(c, "radius")))))
						.then(Commands.argument("from", BlockPosArgument.blockPos())
								.then(Commands.argument("to", BlockPosArgument.blockPos())
										.executes(c -> clear(c.getSource(), between(BlockPosArgument.getBlockPos(c, "from"), BlockPosArgument.getBlockPos(c, "to")))))))));
	}

	private static AABB around(Vec3 at, int radius) {
		return new AABB(at.x - radius, at.y - radius, at.z - radius, at.x + radius, at.y + radius, at.z + radius);
	}

	/** Every block from one corner to the other, both included. */
	private static AABB between(BlockPos from, BlockPos to) {
		return new AABB(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()),
				Math.max(from.getX(), to.getX()) + 1, Math.max(from.getY(), to.getY()) + 1, Math.max(from.getZ(), to.getZ()) + 1);
	}

	private static int clear(CommandSourceStack source, AABB box) {
		ServerLevel level = source.getLevel();
		int bodies = KeptBodies.removeIn(level, box);
		int pieces = EvidenceStore.removeIn(level, box);
		GoreClear payload = new GoreClear(box);
		for (ServerPlayer player : level.players()) {
			if (ServerPlayNetworking.canSend(player, GoreClear.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}

		source.sendSuccess(() -> Component.literal("Cleared the gore there: " + bodies + (bodies == 1 ? " body" : " bodies") + " and "
				+ pieces + (pieces == 1 ? " recorded piece" : " recorded pieces") + " removed."), true);
		return bodies;
	}
}
