package dev.fluidgore.demo;

import java.util.function.BiConsumer;

/**
 * A scene from the automated game test, replayable in game with {@code /gore demo <name>}.
 *
 * @param name        command name
 * @param version     the release that added what it shows
 * @param description one line for the list
 * @param destructive it changes the terrain (digs, blows up), so it asks to be run twice
 * @param build       writes the steps for a stage in front of the player
 */
public record Demo(String name, String version, String description, boolean destructive, BiConsumer<Script, Stage> build) {
}
