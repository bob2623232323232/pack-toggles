package dev.packtoggles;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Particle scenes drawn behind the UI (one per theme). Coordinates are relative to the panel. */
public class FX {
    private static final class P { float x, y, vx, vy, size, life, max, ph; int ch; boolean shoot; }

    private final List<P> ps = new ArrayList<>();
    private final Random r = new Random();
    private float time, spawnAcc, flash, special = 4;
    private Theme.Kind last;

    private static int a(int rgb, float alpha) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    public void update(float dt, Theme t, int w, int h, int intensity) {
        time += dt;
        if (last != t.kind()) { ps.clear(); last = t.kind(); flash = 0; special = 3; if (t.kind() == Theme.Kind.STARS || t.kind() == Theme.Kind.VOID) seedStars(w, h); }
        float m = intensity == 0 ? 0.5f : intensity == 2 ? 2f : 1f;
        float rate = switch (t.kind()) {
            case EMBER -> 14; case SNOW -> 12; case PETAL -> 6; case BUBBLES -> 6; case DIGITAL -> 9; case GOLD -> 10;
            case RAIN -> 45; case FOG -> 5; case FIREFLY -> 3; default -> 0;
        } * m;
        spawnAcc += rate * dt;
        while (spawnAcc >= 1 && ps.size() < 400) { spawnAcc -= 1; spawn(t, w, h); }
        if (spawnAcc > 5) spawnAcc = 0;

        special -= dt;
        if (special <= 0) {
            special = 3 + r.nextFloat() * 5;
            if (t.kind() == Theme.Kind.STARS || t.kind() == Theme.Kind.VOID) {
                P p = new P(); p.shoot = true; p.x = r.nextFloat() * w * 0.6f; p.y = r.nextFloat() * h * 0.4f;
                p.vx = 150 + r.nextFloat() * 80; p.vy = 60 + r.nextFloat() * 50; p.max = p.life = 0.9f; ps.add(p);
            } else if (t.kind() == Theme.Kind.RAIN) flash = 1;
        }
        flash = Math.max(0, flash - dt * 2.5f);

        for (int i = ps.size() - 1; i >= 0; i--) {
            P p = ps.get(i);
            p.ph += dt * (1.5f + p.size);
            switch (t.kind()) {
                case SNOW, PETAL -> p.vx = (float) Math.sin(p.ph) * (t.kind() == Theme.Kind.PETAL ? 18 : 9);
                case BUBBLES -> p.vx = (float) Math.sin(p.ph * 1.3f) * 7;
                case FIREFLY -> { p.vx += (r.nextFloat() - 0.5f) * 40 * dt; p.vy += (r.nextFloat() - 0.5f) * 40 * dt; p.vx *= 0.99f; p.vy *= 0.99f; }
                case DIGITAL -> { if (r.nextInt(12) == 0) p.ch = r.nextInt(16); }
                default -> { }
            }
            p.x += p.vx * dt; p.y += p.vy * dt; p.life -= dt;
            boolean twinkleStar = (t.kind() == Theme.Kind.STARS || t.kind() == Theme.Kind.VOID) && !p.shoot;
            if (twinkleStar) { p.life = p.max; if (t.kind() == Theme.Kind.VOID) p.x -= 3 * dt; if (p.x < 0) p.x += w; }
            if (p.life <= 0 || p.y > h + 12 || p.y < -14 || p.x < -14 || p.x > w + 14) ps.remove(i);
        }
    }

    private void seedStars(int w, int h) {
        for (int i = 0; i < 70; i++) {
            P p = new P(); p.x = r.nextFloat() * w; p.y = r.nextFloat() * h; p.size = 1 + r.nextInt(2);
            p.ph = r.nextFloat() * 6.28f; p.max = p.life = 1; ps.add(p);
        }
    }

    private void spawn(Theme t, int w, int h) {
        P p = new P();
        p.x = r.nextFloat() * w; p.ph = r.nextFloat() * 6.28f; p.size = 1 + r.nextInt(2);
        switch (t.kind()) {
            case EMBER -> { p.y = h + 2; p.vy = -(18 + r.nextFloat() * 50); p.vx = (r.nextFloat() - 0.5f) * 14; p.max = p.life = 3 + r.nextFloat() * 3; }
            case SNOW -> { p.y = -2; p.vy = 14 + r.nextFloat() * 24; p.max = p.life = 12; }
            case PETAL -> { p.y = -2; p.vy = 16 + r.nextFloat() * 20; p.size = 2 + r.nextInt(2); p.max = p.life = 14; }
            case BUBBLES -> { p.y = h + 2; p.vy = -(12 + r.nextFloat() * 28); p.size = 2 + r.nextInt(3); p.max = p.life = 12; }
            case DIGITAL -> { p.y = -6; p.vy = 40 + r.nextFloat() * 40; p.ch = r.nextInt(16); p.max = p.life = 8; }
            case GOLD -> { p.y = r.nextFloat() * h; p.max = p.life = 0.8f + r.nextFloat(); p.size = 1 + r.nextInt(2); }
            case RAIN -> { p.y = -8; p.vy = 160 + r.nextFloat() * 80; p.vx = -25; p.max = p.life = 3; }
            case FOG -> { p.y = h + 2; p.vy = -(8 + r.nextFloat() * 18); p.vx = (r.nextFloat() - 0.5f) * 8; p.max = p.life = 6 + r.nextFloat() * 4; }
            case FIREFLY -> { p.y = r.nextFloat() * h; p.vx = (r.nextFloat() - 0.5f) * 14; p.vy = (r.nextFloat() - 0.5f) * 14; p.size = 2; p.max = p.life = 6 + r.nextFloat() * 6; }
            default -> { return; }
        }
        ps.add(p);
    }

    private static void disc(GuiGraphics g, int cx, int cy, int rad, int c) {
        for (int dy = -rad; dy <= rad; dy++) {
            int dx = (int) Math.sqrt(rad * rad - dy * dy);
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, c);
        }
    }

    public void draw(GuiGraphics g, Font font, Theme t, int ox, int oy, int w, int h) {
        int acc = t.accent(), acc2 = t.accent2();
        switch (t.kind()) {
            case GRID -> drawGrid(g, ox, oy, w, h, acc, acc2);
            case FOG -> {
                for (int i = 0; i < 3; i++) {
                    float al = 0.05f + 0.03f * (float) Math.sin(time * 0.5f + i * 2);
                    int by = oy + h - 24 - i * 14;
                    g.fill(ox, by, ox + w, by + 14, a(acc, al));
                }
                int mx = ox + (int) (w * 0.78f), my = oy + (int) (h * 0.2f + 4 * Math.sin(time * 0.4f));
                disc(g, mx, my, 26, a(acc, 0.06f)); disc(g, mx, my, 20, a(acc, 0.10f)); disc(g, mx, my, 14, a(acc, 0.35f));
            }
            case RAIN -> { if (flash > 0) g.fill(ox, oy, ox + w, oy + h, a(0xFFFFFF, flash * 0.14f)); }
            default -> { }
        }
        for (P p : ps) {
            int x = ox + (int) p.x, y = oy + (int) p.y;
            float f = p.life / Math.max(0.01f, p.max);
            switch (t.kind()) {
                case EMBER -> { float al = Math.min(1, f * 2) * (0.5f + 0.5f * (float) Math.sin(p.ph * 4)); g.fill(x, y, x + (int) p.size, y + (int) p.size, a(p.size > 1 ? acc2 : acc, al)); }
                case SNOW -> g.fill(x, y, x + (int) p.size, y + (int) p.size, a(acc2, 0.8f));
                case PETAL -> { g.fill(x, y, x + (int) p.size + 1, y + (int) p.size, a(acc, 0.85f)); g.fill(x + 1, y - 1, x + (int) p.size, y, a(acc2, 0.7f)); }
                case BUBBLES -> { int s = (int) p.size + 1; int c = a(acc2, 0.6f);
                    g.fill(x, y, x + s, y + 1, c); g.fill(x, y + s - 1, x + s, y + s, c); g.fill(x, y, x + 1, y + s, c); g.fill(x + s - 1, y, x + s, y + s, c); }
                case DIGITAL -> { g.drawString(font, Integer.toHexString(p.ch).toUpperCase(), x, y, a(acc, 0.55f), false);
                    g.drawString(font, Integer.toHexString((p.ch + 5) & 15).toUpperCase(), x, y - 9, a(acc, 0.22f), false); }
                case GOLD -> { float al = (float) Math.sin(Math.PI * f); int c = a(acc2, al * 0.9f);
                    g.fill(x, y, x + 1, y + 1, c); g.fill(x - 1, y, x, y + 1, a(acc2, al * 0.4f)); g.fill(x + 1, y, x + 2, y + 1, a(acc2, al * 0.4f));
                    g.fill(x, y - 1, x + 1, y, a(acc2, al * 0.4f)); g.fill(x, y + 1, x + 1, y + 2, a(acc2, al * 0.4f)); }
                case RAIN -> g.fill(x, y, x + 1, y + 6, a(acc, 0.5f));
                case FOG -> { float al = Math.min(1, f * 2) * 0.5f; g.fill(x, y, x + (int) p.size, y + (int) p.size, a(acc2, al)); }
                case FIREFLY -> { float al = 0.5f + 0.5f * (float) Math.sin(p.ph * 2); g.fill(x - 2, y - 2, x + 4, y + 4, a(acc2, al * 0.08f));
                    g.fill(x - 1, y - 1, x + 3, y + 3, a(acc2, al * 0.18f)); g.fill(x, y, x + 2, y + 2, a(acc2, 0.4f + al * 0.6f)); }
                case STARS, VOID -> {
                    if (p.shoot) { float al = f; for (int i = 0; i < 8; i++) g.fill(x - i * 3, y - i, x - i * 3 + 2, y - i + 1, a(acc2, al * (1 - i / 8f)));
                        g.fill(x, y, x + 2, y + 2, a(0xFFFFFF, al)); }
                    else { float al = 0.25f + 0.55f * (0.5f + 0.5f * (float) Math.sin(time * 1.7f + p.ph));
                        g.fill(x, y, x + (int) p.size, y + (int) p.size, a((p.ph > 3.1f) ? acc2 : 0xFFFFFF, al)); }
                }
                default -> { }
            }
        }
    }

    private void drawGrid(GuiGraphics g, int ox, int oy, int w, int h, int acc, int acc2) {
        int horizon = oy + (int) (h * 0.62f);
        disc(g, ox + w / 2, horizon, 22, a(acc, 0.10f)); // sun glow
        disc(g, ox + w / 2, horizon, 14, a(acc, 0.22f));
        float off = (time * 0.35f) % 1f;
        for (int i = 0; i < 9; i++) {
            float k = (i + off) / 9f;
            int y = horizon + (int) (k * k * (oy + h - horizon));
            g.fill(ox, y, ox + w, y + 1, a(acc2, 0.10f + 0.2f * k));
        }
        for (int k = -8; k <= 8; k++) {
            int x0 = ox + w / 2, y0 = horizon, x1 = ox + w / 2 + k * (w / 6), y1 = oy + h;
            int steps = Math.max(1, y1 - y0);
            for (int s = 0; s < steps; s += 2) {
                float q = s / (float) steps;
                g.fill(x0 + (int) ((x1 - x0) * q), y0 + s, x0 + (int) ((x1 - x0) * q) + 1, y0 + s + 1, a(acc, 0.05f + 0.15f * q));
            }
        }
    }
}
