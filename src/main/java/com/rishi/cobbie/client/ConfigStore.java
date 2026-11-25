package com.rishi.cobbie.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The config screen's save path, as a file operation with no screen attached.
 *
 * <p>Exists because of a lesson the panel-versus-chat work taught: **a screen that writes as you type is a screen
 * that can lie about what is configured.** Edits belong to a draft, and the file changes only when Save is pressed —
 * so the thing worth testing is not the drawing but the file: saving writes it, and closing without saving leaves it
 * exactly as it was.
 *
 * <p>Atomic on purpose: a crash mid-write must not leave a half-written config, which is the one file whose damage
 * a player cannot fix from inside the game.
 */
public final class ConfigStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigStore() {}

    /** Writes the object atomically. Returns null on success, or the reason it failed. */
    public static String write(Path file, JsonObject content) {
        if (file == null) return "there is no config file to write";
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(content));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException noAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (IOException e) {
            return "I could not write the config file: " + e.getMessage();
        }
    }
}
