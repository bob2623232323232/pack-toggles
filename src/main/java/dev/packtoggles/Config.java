package dev.packtoggles;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("packtoggles.json");

    /** File/folder names (inside resourcepacks/) of the packs the toggles apply to. */
    public static final Set<String> sources = new LinkedHashSet<>();
    public static final Set<String> disabled = new HashSet<>();
    public static String theme = "inferno";
    public static boolean anim = true;
    public static boolean glow = true;
    /** 0 = low, 1 = medium, 2 = high particle amount */
    public static int intensity = 1;

    public static void load() {
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            sources.clear(); disabled.clear();
            if (o.has("sources")) o.getAsJsonArray("sources").forEach(e -> sources.add(e.getAsString()));
            else if (o.has("source") && !o.get("source").getAsString().isEmpty()) sources.add(o.get("source").getAsString());
            if (o.has("disabled")) o.getAsJsonArray("disabled").forEach(e -> disabled.add(e.getAsString()));
            if (o.has("theme")) theme = o.get("theme").getAsString();
            if (o.has("anim")) anim = o.get("anim").getAsBoolean();
            if (o.has("glow")) glow = o.get("glow").getAsBoolean();
            if (o.has("intensity")) intensity = Math.max(0, Math.min(2, o.get("intensity").getAsInt()));
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Could not read config", e);
        }
    }

    public static void save() {
        try {
            JsonObject o = new JsonObject();
            JsonArray s = new JsonArray(); sources.forEach(s::add); o.add("sources", s);
            JsonArray a = new JsonArray(); disabled.stream().sorted().forEach(a::add); o.add("disabled", a);
            o.addProperty("theme", theme);
            o.addProperty("anim", anim);
            o.addProperty("glow", glow);
            o.addProperty("intensity", intensity);
            Files.writeString(FILE, GSON.toJson(o));
        } catch (IOException e) {
            PackTogglesClient.LOG.warn("Could not save config", e);
        }
    }
}
