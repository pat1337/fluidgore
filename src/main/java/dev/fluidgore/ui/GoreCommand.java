package dev.fluidgore.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.demo.Demo;
import dev.fluidgore.demo.DemoPlayer;
import dev.fluidgore.demo.Demos;
import dev.fluidgore.demo.Script;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * {@code /gore demo}: lists the demos; {@code /gore demo <name>} plays one in front of you; {@code stop} ends it and
 * {@code clear} removes what demos summoned. A client command: the server never sees {@code /gore}, only the vanilla
 * commands a demo sends, which need cheats (singleplayer) or operator rights (server).
 */
public final class GoreCommand {
	/** A destructive demo must be asked for twice within this many milliseconds. */
	private static final long CONFIRM_MS = 15_000L;

	private static String armed;
	private static long armedAt;

	private GoreCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("gore")
				.then(ClientCommands.literal("demo")
						.executes(ctx -> list(ctx.getSource()))
						.then(ClientCommands.literal("stop").executes(ctx -> {
							DemoPlayer.stop(ctx.getSource().getClient());
							ctx.getSource().sendFeedback(Component.literal("Demo stopped."));
							return 1;
						}))
						.then(ClientCommands.literal("clear").executes(ctx -> {
							if (!checkAllowed(ctx.getSource())) {
								return 0;
							}

							DemoPlayer.stop(ctx.getSource().getClient());
							ctx.getSource().getPlayer().connection.sendCommand("kill @e[tag=" + Script.TAG + "]");
							return 1;
						}))
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Demos.all().stream().map(Demo::name), builder))
								.executes(ctx -> play(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
	}

	private static int list(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("Fluid Gore demos (click one, or /gore demo <name>):").withStyle(ChatFormatting.GOLD));
		for (Demo demo : Demos.all()) {
			MutableComponent line = Component.literal(" " + demo.name()).withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
					.withClickEvent(new ClickEvent.RunCommand("/gore demo " + demo.name()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Play " + demo.name()))));
			line.append(Component.literal(" (" + demo.version() + ") " + demo.description() + (demo.destructive() ? " [digs and blows up]" : ""))
					.withStyle(ChatFormatting.GRAY));
			source.sendFeedback(line);
		}

		source.sendFeedback(Component.literal("Needs cheats or op. /gore demo stop, /gore demo clear.").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int play(FabricClientCommandSource source, String name) {
		Demo demo = Demos.get(name);
		if (demo == null) {
			source.sendError(Component.literal("No demo called " + name + ". /gore demo lists them."));
			return 0;
		}

		if (!checkAllowed(source)) {
			return 0;
		}

		long now = System.currentTimeMillis();
		if (demo.destructive() && !(name.equals(armed) && now - armedAt < CONFIRM_MS)) {
			armed = name;
			armedAt = now;
			source.sendFeedback(Component.literal("This one digs into the ground in front of you and blows it up. ")
					.withStyle(ChatFormatting.YELLOW)
					.append(Component.literal("[Click to go]").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
							.withClickEvent(new ClickEvent.RunCommand("/gore demo " + name)))));
			return 1;
		}

		armed = null;
		Minecraft mc = source.getClient();
		GoreLevel level = GoreConfig.get().goreLevel;
		if (!level.enabled()) {
			source.sendError(Component.literal("Gore Level is Off. Turn it on in the Fluid Gore settings first."));
			return 0;
		}

		if (level != GoreLevel.EXTREME) {
			source.sendFeedback(Component.literal("Gore Level is " + level + "; Extreme shows the most.").withStyle(ChatFormatting.GRAY));
		}

		DemoPlayer.start(mc, demo);
		return 1;
	}

	private static boolean checkAllowed(FabricClientCommandSource source) {
		if (DemoPlayer.allowed(source.getClient())) {
			return true;
		}

		source.sendError(Component.literal("Demos summon animals with commands: turn cheats on (singleplayer: Open to LAN, Allow Cheats) or be an operator."));
		return false;
	}
}
