package dev.packtoggles;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tiny script language for driving your own player (walk, run, jump...). Steps are separated by ; or new lines.
 * Scripts are started from the Scripts page or from Discord (only for the user ID you set).
 */
final class Scripts {
    private record Step(String cmd, double a, double b) { }

    static final String HELP = "walk/run/back/left/right/sneak/use <sec>, jump/attack <times>, wait <sec>, look <yaw> <pitch>, turn <deg>, repeat <n> ... end, stop";
    private static final int MAX_STEPS = 600;
    private static final long MAX_RUN_MS = 5 * 60_000L;

    private static final List<Step> queue = new ArrayList<>();
    private static final List<KeyMapping> held = new ArrayList<>();
    private static int pos = 0, hold = 0;
    private static volatile boolean active = false;
    private static long deadline = 0;

    private static final Map<String, String> saved = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static boolean loaded = false;

    private Scripts() { }

    static boolean isActive() { return active; }

    // ---------------- parsing ----------------
    private static double num(String[] w, int i, double def) {
        if (w.length <= i) return def;
        try { return Double.parseDouble(w[i]); } catch (NumberFormatException e) { throw new IllegalArgumentException("Not a number: " + w[i]); }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static List<Step> parse(String text) {
        List<String> tokens = new ArrayList<>();
        for (String t : text.split("[;\\n]")) { t = t.trim(); if (!t.isEmpty()) tokens.add(t); }
        return block(tokens, new int[]{0}, false, 0);
    }

    private static List<Step> block(List<String> tk, int[] i, boolean inner, int depth) {
        List<Step> out = new ArrayList<>();
        while (i[0] < tk.size()) {
            String[] w = tk.get(i[0]++).toLowerCase(Locale.ROOT).split("\\s+");
            String c = w[0];
            if (c.equals("end")) {
                if (inner) return out;
                throw new IllegalArgumentException("'end' without 'repeat'");
            }
            if (c.equals("repeat")) {
                if (depth >= 3) throw new IllegalArgumentException("repeat is nested too deep");
                int n = (int) clamp(num(w, 1, 1), 1, 100);
                List<Step> body = block(tk, i, true, depth + 1);
                for (int k = 0; k < n; k++) {
                    out.addAll(body);
                    if (out.size() > MAX_STEPS) throw new IllegalArgumentException("script is too long (max " + MAX_STEPS + " steps)");
                }
                continue;
            }
            switch (c) {
                case "walk", "forward", "run", "back", "left", "right", "sneak", "use" -> out.add(new Step(c, clamp(num(w, 1, 1), 0.05, 60), 0));
                case "wait", "sleep" -> out.add(new Step("wait", clamp(num(w, 1, 1), 0.05, 60), 0));
                case "jump", "attack" -> {
                    int n = (int) clamp(num(w, 1, 1), 1, 50);
                    for (int k = 0; k < n; k++) {
                        out.add(new Step(c, 0, 0));
                        out.add(new Step("wait", c.equals("jump") ? 0.45 : 0.6, 0));
                    }
                }
                case "look" -> out.add(new Step("look", num(w, 1, 0), clamp(num(w, 2, 0), -90, 90)));
                case "turn" -> out.add(new Step("turn", num(w, 1, 90), 0));
                case "stop" -> out.add(new Step("stop", 0, 0));
                default -> throw new IllegalArgumentException("Unknown step '" + c + "'");
            }
            if (out.size() > MAX_STEPS) throw new IllegalArgumentException("script is too long (max " + MAX_STEPS + " steps)");
        }
        if (inner) throw new IllegalArgumentException("'repeat' needs an 'end'");
        return out;
    }

    // ---------------- running ----------------
    static String run(String text) {
        List<Step> steps;
        try { steps = parse(text == null ? "" : text); } catch (IllegalArgumentException e) { return "Script error: " + e.getMessage(); }
        if (steps.isEmpty()) return "Nothing to run. Example: `walk 3; jump; run 2`";
        final List<Step> s = steps;
        Minecraft.getInstance().execute(() -> {
            finish();
            queue.addAll(s);
            active = true;
            deadline = System.currentTimeMillis() + MAX_RUN_MS;
        });
        return "Running " + steps.size() + " steps";
    }

    static String runNamed(String name) {
        String t = get(name);
        return t == null ? "No saved script called `" + name + "`" : run(t);
    }

    static String stop() {
        Minecraft.getInstance().execute(Scripts::finish);
        return "Stopped";
    }

    private static void hold(KeyMapping k) {
        if (!held.contains(k)) held.add(k);
        k.setDown(true);
    }

    private static void press(Minecraft mc, String c) {
        var o = mc.options;
        switch (c) {
            case "walk", "forward" -> hold(o.keyUp);
            case "run" -> { hold(o.keyUp); hold(o.keySprint); }
            case "back" -> hold(o.keyDown);
            case "left" -> hold(o.keyLeft);
            case "right" -> hold(o.keyRight);
            case "sneak" -> hold(o.keyShift);
            case "use" -> hold(o.keyUse);
            case "jump" -> hold(o.keyJump);
            case "attack" -> hold(o.keyAttack);
            default -> { }
        }
    }

    private static void releaseKeys() {
        for (KeyMapping k : held) k.setDown(false);
        held.clear();
    }

    private static void finish() {
        active = false;
        releaseKeys();
        queue.clear();
        pos = 0;
        hold = 0;
    }

    private static int ticks(double sec) { return Math.max(1, (int) Math.round(sec * 20)); }

    /** Called every client tick (game thread). */
    static void tick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || System.currentTimeMillis() > deadline) { finish(); return; }
        if (hold > 0) {
            hold--;
            if (hold > 0) { for (KeyMapping k : held) k.setDown(true); return; }
            releaseKeys();
        }
        while (pos < queue.size()) {
            Step s = queue.get(pos++);
            switch (s.cmd()) {
                case "wait" -> { hold = ticks(s.a()); return; }
                case "look" -> { mc.player.setYRot((float) s.a()); mc.player.setXRot((float) s.b()); }
                case "turn" -> mc.player.setYRot(mc.player.getYRot() + (float) s.a());
                case "stop" -> { finish(); return; }
                default -> {
                    press(mc, s.cmd());
                    hold = (s.cmd().equals("jump") || s.cmd().equals("attack")) ? 2 : ticks(s.a());
                    return;
                }
            }
        }
        finish();
    }

    // ---------------- saved scripts ----------------
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("trim-studio-scripts.txt"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file())) {
                String[] t = line.split("\t", 2);
                if (t.length == 2 && !t[0].isBlank()) saved.put(t[0], t[1]);
            }
        } catch (Exception ignored) { }
    }

    private static void persist() {
        try {
            List<String> lines = new ArrayList<>();
            for (var e : saved.entrySet()) lines.add(e.getKey() + "\t" + e.getValue());
            Files.write(file(), lines);
        } catch (Exception ignored) { }
    }

    static synchronized List<String> names() { load(); return new ArrayList<>(saved.keySet()); }

    static synchronized String get(String name) { load(); return name == null ? null : saved.get(name.trim()); }

    static synchronized String save(String name, String text) {
        load();
        String n = name == null ? "" : name.trim();
        if (!n.matches("[A-Za-z0-9_-]{1,24}")) return "Name must be 1-24 letters, numbers, - or _";
        String t = (text == null ? "" : text).replaceAll("[\\t\\r\\n]+", "; ").trim();
        try { if (parse(t).isEmpty()) return "Script is empty"; } catch (IllegalArgumentException e) { return "Script error: " + e.getMessage(); }
        if (!saved.containsKey(n) && saved.size() >= 50) return "Too many saved scripts (50)";
        saved.put(n, t);
        persist();
        return "Saved script `" + n + "`";
    }

    static synchronized String delete(String name) {
        load();
        if (name == null || saved.remove(name.trim()) == null) return "No saved script called `" + (name == null ? "" : name.trim()) + "`";
        persist();
        return "Deleted `" + name.trim() + "`";
    }

    static synchronized String list() {
        load();
        if (saved.isEmpty()) return "No saved scripts yet. Use `!savescript <name> <steps>`";
        StringBuilder sb = new StringBuilder("**Scripts:**");
        for (var e : saved.entrySet()) sb.append("\n`").append(e.getKey()).append("`: ").append(e.getValue());
        return sb.toString();
    }
}
