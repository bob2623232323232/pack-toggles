package dev.packtoggles;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Hooks that must exist from game start: auto connect on join, script ticking and combo tracking. Call Extras.init() once from your client initializer.
 */
final class Extras {
    static volatile boolean comboHud = true;

    // combo tracking
    static int combo = 0, bestCombo = 0, hits = 0, taken = 0;
    static long lastHit = 0;
    static String lastTarget = "";
    static final Deque<Integer> history = new ArrayDeque<>();
    static final long WINDOW_MS = 2000;
    private static int prevHurt = 0;
    private static boolean inited = false;

    private Extras() { }

    static void init() {
        if (inited) return;
        inited = true;
        ToggleScreen.loadPrefs();
        ClientTickEvents.END_CLIENT_TICK.register(Extras::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ToggleScreen.loadPrefs();
            if (DiscordBot.autoConnect && !DiscordBot.isRunning() && !DiscordBot.token.isBlank() && (!DiscordBot.channelId.isBlank() || !DiscordBot.chatChannelId.isBlank())) DiscordBot.start();
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
        if (mc.player == null) { prevHurt = 0; return; }
        int h = mc.player.hurtTime;
        if (h > 0 && prevHurt == 0) { taken++; endCombo(); }
        prevHurt = h;
        if (combo > 0 && System.currentTimeMillis() - lastHit > WINDOW_MS) endCombo();
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
    }
}
