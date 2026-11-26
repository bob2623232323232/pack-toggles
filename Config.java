package dev.packtoggles;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("packtoggles.json");

    /** File name (inside resourcepacks/) of the original full pack. Empty = not chosen yet. */
    public static String source = "";
    public static final Set<String> disabled = new HashSet<>();

    public static void load() {
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (o.has("source")) source = o.get("source").getAsString();
            disabled.clear();
            if (o.has("disabled")) o.getAsJsonArray("disabled").forEach(e -> disabled.add(e.getAsString()));
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Could not read config", e);
        }
    }

    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("source", source);
            JsonArray a = new JsonArray();
            disabled.stream().sorted().forEach(a::add);
            o.add("disabled", a);
            Files.writeString(FILE, GSON.toJson(o));
        } catch (IOException e) {
            PackTogglesClient.LOG.warn("Could not save config", e);
        }
    }
}
