package dev.fluidgore.demo;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Plays one {@link Demo} at a time from the client tick. */
public final class DemoPlayer {
	/** Steps run in one tick at most, so a script without pauses cannot freeze the game. */
	private static final int MAX_STEPS_PER_TICK = 64;

	private static @Nullable Demo playing;
	private static @Nullable Script script;
	private static int next;
	private static int wait;
	/** What the player "holds" for the gore while a demo runs; null = their real main-hand item. */
	private static @Nullable ItemStack held;
	/** Ticks left to keep command echoes out of the chat after a demo ends (the last replies arrive late). */
	private static int quiet;

	private DemoPlayer() {
	}

	public static boolean playing() {
		return playing != null;
	}

	/** True if the player may run the commands a demo needs (cheats on in singleplayer, or operator on a server). */
	public static boolean allowed(Minecraft mc) {
		return mc.player != null && mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** Starts {@code demo} in front of the player, replacing any demo already running. */
	public static void start(Minecraft mc, Demo demo) {
		stop(mc);
		Stage stage = new Stage(mc.player);
		Script s = new Script();
		// Through execute, so an empty stage clears silently instead of failing with "No entity was found".
		s.cmd("execute as @e[tag=" + Script.TAG + "] run kill @s");
		s.pause(2);
		demo.build().accept(s, stage);
		playing = demo;
		script = s;
		next = 0;
		wait = 0;
	}

	/** Stops the running demo (what it summoned stays) and gives the player back their own hands. */
	public static void stop(Minecraft mc) {
		if (playing != null) {
			quiet = 40;
		}

		playing = null;
		script = null;
		held = null;
	}

	public static void tick(Minecraft mc) {
		if (quiet > 0) {
			quiet--;
		}

		if (script == null) {
			return;
		}

		if (mc.player == null || mc.level == null) {
			playing = null;
			script = null;
			held = null;
			return;
		}

		if (wait > 0) {
			wait--;
			return;
		}

		for (int n = 0; n < MAX_STEPS_PER_TICK && next < script.steps.size(); n++) {
			Script.Step step = script.steps.get(next++);
			if (step.action() == null) {
				wait = step.ticks() - 1;
				return;
			}

			step.action().accept(mc);
		}

		if (next >= script.steps.size()) {
			say(mc, "Demo over: " + playing.name());
			stop(mc);
		}
	}

	/**
	 * False for the replies to the commands a demo sends ("Summoned new Cow", "Applied 2 damage", "No entity was
	 * found"), so the chat stays clean during the show. Everything else, and every message outside a demo, gets through.
	 */
	public static boolean allowMessage(Component message) {
		if (playing == null && quiet <= 0) {
			return true;
		}

		return !(message.getContents() instanceof TranslatableContents t && (t.getKey().startsWith("commands.") || t.getKey().startsWith("argument.")));
	}

	/** The item {@code attacker} struck with: its main-hand item, or what a running demo says the player holds. */
	public static ItemStack held(LivingEntity attacker) {
		Minecraft mc = Minecraft.getInstance();
		return held != null && attacker == mc.player ? held : attacker.getMainHandItem();
	}

	static void hold(ItemStack stack) {
		held = stack;
	}

	static void say(Minecraft mc, String text) {
		mc.player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
	}
}
