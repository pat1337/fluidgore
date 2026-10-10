package dev.fluidgore.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The steps of a demo, run one after another from the client tick: vanilla commands sent as the player (so they need
 * cheats or operator rights, and nothing on the server side), things done on the client, narration, and pauses.
 */
public final class Script {
	/** Tag on everything a demo summons, so the next demo (or {@code /gore demo clear}) can remove it. */
	public static final String TAG = "fluidgore_demo";

	/** One step: an action, or (if null) a pause of {@code ticks}. */
	record Step(@Nullable Consumer<Minecraft> action, int ticks) {
	}

	final List<Step> steps = new ArrayList<>();

	/** Sends a command as the player, e.g. {@code summon minecraft:pig ...} (no leading slash). */
	public Script cmd(String command) {
		steps.add(new Step(mc -> mc.player.connection.sendCommand(command), 0));
		return this;
	}

	/**
	 * Summons a still mob (no AI) at a stage spot, turned {@code turn} degrees from the player's facing, tagged with
	 * {@code role} so later commands can find it. {@code extra} is more NBT (like {@code Age:-24000}), or empty.
	 */
	public Script summon(Stage stage, String type, String role, double right, double up, double forward, float turn, String extra) {
		return cmd("summon minecraft:" + type + " " + stage.pos(right, up, forward) + " {NoAI:1b,Rotation:[" + stage.yaw(turn) + "f,0f],Tags:[\"" + TAG
				+ "\",\"" + role + "\"]" + (extra.isEmpty() ? "" : "," + extra) + "}");
	}

	/** The player strikes the mob tagged {@code role} for {@code damage} (a melee hit, with whatever {@link #hold} says). */
	public Script hit(String role, float damage) {
		return cmd("damage @e[tag=" + role + ",limit=1] " + damage + " minecraft:player_attack by @s");
	}

	/**
	 * What the player strikes with for the rest of the demo: an item id like {@code minecraft:iron_sword}, or
	 * {@code minecraft:air} for bare hands. Only the gore sees it; the player's inventory is left alone.
	 */
	public Script hold(String item) {
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item)));
		steps.add(new Step(mc -> DemoPlayer.hold(stack), 0));
		return this;
	}

	/** Shows {@code text} over the hotbar. */
	public Script say(String text) {
		steps.add(new Step(mc -> DemoPlayer.say(mc, text), 0));
		return this;
	}

	/** Runs {@code action} on the client. */
	public Script client(Consumer<Minecraft> action) {
		steps.add(new Step(action, 0));
		return this;
	}

	/** Waits {@code ticks} (20 to the second). */
	public Script pause(int ticks) {
		steps.add(new Step(null, ticks));
		return this;
	}
}
