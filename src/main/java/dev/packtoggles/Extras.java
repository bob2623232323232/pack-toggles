package dev.packtoggles;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Hooks that must exist from game start: auto connect on join, script ticking, combo tracking,
 * waypoint HUD and death waypoints. Call Extras.init() once from your client initializer.
 */
final class Extras {
    static volatile boolean wpHud = true, wpDeath = true, comboHud = true;

    // combo tracking
    static int combo = 0, bestCombo = 0, hits = 0, taken = 0;
    static long lastHit = 0;
    static String lastTarget = "";
    static final Deque<Integer> history = new ArrayDeque<>();
    static final long WINDOW_MS = 2000;
    private static int prevHurt = 0;
    private static boolean prevDead = false, inited = false;

    // waypoint cache (the HUD must not read the file every frame)
    private static List<String[]> wpCache = new ArrayList<>();
    static volatile long wpAt = 0;
    static final int[] WP_COL = {0xFFE0564F, 0xFFF0B429, 0xFF58D26F, 0xFF4FB3E8, 0xFF9B5CFF, 0xFFFF8FB8, 0xFF1BC9B0, 0xFFFFFFFF};

    private Extras() { }

    static void init() {
        if (inited) return;
        inited = true;
        ToggleScreen.loadPrefs();
        ClientTickEvents.END_CLIENT_TICK.register(Extras::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ToggleScreen.loadPrefs();
            if (DiscordBot.autoConnect && !DiscordBot.isRunning() && !DiscordBot.token.isBlank() && !DiscordBot.channelId.isBlank()) DiscordBot.start();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Scripts.stop());
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player && entity instanceof LivingEntity le && le.isAlive()) {
                hits++;
                combo++;
                bestCombo = Math.max(bestCombo, combo);
                lastHit = System.currentTimeMillis();
                lastTarget = entity.getName().getString();
            }
            return InteractionResult.PASS;
        });
        HudRenderCallback.EVENT.register(Extras::hud);
    }

    // ---------------- combo ----------------
    private static void endCombo() {
        if (combo >= 2) {
            history.addFirst(combo);
            while (history.size() > 10) history.removeLast();
        }
        combo = 0;
    }

    static void resetCombo() {
        combo = 0; bestCombo = 0; hits = 0; taken = 0; lastTarget = "";
        history.clear();
    }

    private static void tick(Minecraft mc) {
        Scripts.tick(mc);
        if (mc.player == null) { prevHurt = 0; prevDead = false; return; }
        int h = mc.player.hurtTime;
        if (h > 0 && prevHurt == 0) { taken++; endCombo(); }
        prevHurt = h;
        if (combo > 0 && System.currentTimeMillis() - lastHit > WINDOW_MS) endCombo();
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !prevDead && wpDeath && mc.level != null) ToggleScreen.markHere("Death");
        prevDead = dead;
    }

    // ---------------- waypoints ----------------
    static String dimension(Minecraft mc) {
        String dim = mc.level.dimension().toString();
        int slash = dim.indexOf("/ ");
        if (slash >= 0) dim = dim.substring(slash + 2).replace("]", "").trim();
        return dim;
    }

    /** Waypoint with exactly 7 fields: name x y z dim colorIndex visible. */
    static String[] norm(String[] w) {
        String[] o = new String[7];
        for (int i = 0; i < 7; i++) o[i] = i < w.length ? w[i] : (i == 5 ? "0" : i == 6 ? "1" : "");
        return o;
    }

    static int color(String[] w) {
        try { return WP_COL[Math.abs(Integer.parseInt(norm(w)[5].trim())) % WP_COL.length]; } catch (Exception e) { return WP_COL[0]; }
    }

    static boolean visible(String[] w) { return !"0".equals(norm(w)[6].trim()); }

    private static double d(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    static synchronized List<String[]> waypoints() {
        long now = System.currentTimeMillis();
        if (now - wpAt > 1500) { wpCache = ToggleScreen.loadWaypoints(); wpAt = now; }
        return wpCache;
    }

    // ---------------- HUD ----------------
    private static void hud(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        int w = g.guiWidth();
        if (comboHud && combo > 0) {
            String s = "Combo x" + combo;
            g.drawString(mc.font, s, w / 2 - mc.font.width(s) / 2, g.guiHeight() / 2 + 20, 0xFFFFD23F, true);
        }
        if (!wpHud) return;
        String dim = dimension(mc);
        double px = mc.player.getX(), pz = mc.player.getZ();
        float yaw = mc.player.getYRot();
        for (String[] raw : waypoints()) {
            String[] wp = norm(raw);
            if (!visible(wp) || !wp[4].equals(dim)) continue;
            double dx = d(wp[1]) - px, dz = d(wp[3]) - pz;
            float rel = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
            if (Math.abs(rel) > 50f) continue;
            int x = w / 2 + Math.round(rel / 50f * (w / 3f));
            int col = color(wp);
            g.fill(x - 2, 14, x + 3, 19, col);
            String label = wp[0] + "  " + Math.round(Math.sqrt(dx * dx + dz * dz)) + "m";
            g.drawString(mc.font, label, x - mc.font.width(label) / 2, 22, col, true);
        }
    }
}
