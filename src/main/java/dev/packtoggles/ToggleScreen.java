package dev.packtoggles;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.*;

/**
 * Themed dashboard. Everything is drawn by hand in render(); the vanilla Buttons are fully
 * transparent and only provide the click areas (and hover state).
 */
public class ToggleScreen extends Screen {
    private static final int TEXT = 0xFFF5E8DD, GREEN = 0xFF58D26F, RED = 0xFFE0564F;
    private static final String[] TABS = {"Overview", "Packs", "Sources", "Settings"};
    private static final String[] CHIPS = {"All", "Minecraft", "Mods", "Misc"};
    private static final String[] INTENSITY = {"Low", "Medium", "High"};
    private static final Set<String> MC_IDS = Set.of("blocks", "items", "gui", "entities", "effects", "particles", "ranks", "misc_tex");
    private static final Set<String> MISC_IDS = Set.of("sounds", "shaders", "optifine", "lang", "respackopts",
            "carl", "potions_icons", "visible_cooldown", "entity_features");

    private final Screen parent;
    private final FX fx = new FX();
    private final Map<String, Float> eased = new HashMap<>();
    private final Map<String, Button> hits = new HashMap<>();
    private int tab = 0, chip = 0, page = 0;
    private float openT = 0, tabT = 1;
    private long lastNanos = System.nanoTime();
    private float dt = 0.016f, time = 0;
    private Component status = Component.empty();
    private Theme theme = Theme.byId(Config.theme);
    private int cAccent, cAccent2, cBg, cCard, cLine; // eased theme colours
    private boolean colorsInit = false;

    // layout
    private int px, py, pw, ph, cx, cw, top, bottom, rowH = 22, rows;
    private int applyX, applyY, applyW = 150, applyH = 24;
    private List<Group> rowGroups = new ArrayList<>();
    private List<String> srcRows = new ArrayList<>();

    public ToggleScreen(Screen parent) {
        super(Component.translatable("screen.packtoggles.title"));
        this.parent = parent;
        if (Config.sources.isEmpty()) {
            try { Config.sources.addAll(PackBuilder.enabledInGame()); } catch (Exception ignored) { }
            if (Config.sources.isEmpty()) {
                List<String> c = PackBuilder.candidates();
                if (!c.isEmpty()) Config.sources.add(c.get(0));
            }
        }
    }

    // ---------------- helpers ----------------
    private float ease(String key, float target, float speed) {
        float v = eased.getOrDefault(key, target);
        v += (target - v) * Math.min(1f, dt * speed);
        eased.put(key, v);
        return v;
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static int alpha(int rgb, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static void round(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 2, y, x + w - 2, y + h, c);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, c);
        g.fill(x, y + 2, x + w, y + h - 2, c);
    }

    private static void disc(GuiGraphics g, int x, int y, int r, int c) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            g.fill(x - dx, y + dy, x + dx + 1, y + dy + 1, c);
        }
    }

    private void text(GuiGraphics g, String s, int x, int y, int c) { g.drawString(font, s, x, y, c, false); }

    private void bold(GuiGraphics g, String s, int x, int y, int c) {
        g.drawString(font, Component.literal(s).withStyle(ChatFormatting.BOLD), x, y, c, false);
    }

    private String fit(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "..") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private int muted() { return mix(TEXT, cBg, 0.5f); }

    private void card(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x, y, w, h, cLine);
        round(g, x + 1, y + 1, w - 2, h - 2, cCard);
    }

    private boolean hov(String key) { Button b = hits.get(key); return b != null && b.isHoveredOrFocused(); }

    private void hit(String key, int x, int y, int w, int h, Runnable r) {
        Button b = Button.builder(Component.empty(), btn -> r.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f); // invisible but clickable
        addRenderableWidget(b);
        hits.put(key, b);
    }

    private void setTab(int t) { tab = t; page = 0; tabT = 0; init(); }

    // ---------------- layout / click areas ----------------
    @Override
    protected void init() {
        clearWidgets();
        hits.clear();
        pw = Math.min(width - 24, 560);
        ph = Math.min(height - 16, 340);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 52;
        cw = pw - 60;
        top = py + 34;
        applyY = py + ph - 8 - applyH;
        applyX = cx + cw - applyW;
        bottom = applyY - 8;

        for (int i = 0; i < TABS.length; i++) { final int idx = i; hit("tab" + i, px + 8, py + 44 + i * 34, 34, 28, () -> setTab(idx)); }
        hit("close", px + pw - 26, py + 10, 16, 16, this::onClose);
        hit("apply", applyX, applyY, applyW, applyH, this::apply);

        rowGroups = new ArrayList<>();
        srcRows = new ArrayList<>();
        switch (tab) {
            case 1 -> initPacks();
            case 2 -> initSources();
            case 3 -> initSettings();
            default -> { }
        }
    }

    private List<Group> visible() {
        List<Group> out = new ArrayList<>();
        for (Group g : Group.ALL) {
            boolean mc = MC_IDS.contains(g.id()), misc = MISC_IDS.contains(g.id());
            if (chip == 0 || (chip == 1 && mc) || (chip == 2 && !mc && !misc) || (chip == 3 && misc)) out.add(g);
        }
        return out;
    }

    private void initPacks() {
        int chipX = cx + 8;
        for (int i = 0; i < CHIPS.length; i++) {
            final int idx = i;
            int w = font.width(CHIPS[i]) + 14;
            hit("chip" + i, chipX, top, w, 16, () -> { chip = idx; page = 0; init(); });
            chipX += w + 4;
        }
        hit("allon", cx + cw - 128, top, 36, 16, () -> { for (Group g : visible()) Config.disabled.remove(g.id()); });
        hit("alloff", cx + cw - 88, top, 38, 16, () -> { for (Group g : visible()) Config.disabled.add(g.id()); });
        rows = Math.max(1, (bottom - top - 26 - 6) / rowH);
        List<Group> vis = visible();
        int pages = Math.max(1, (vis.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows; i++) {
            int idx = page * rows + i;
            if (idx >= vis.size()) break;
            Group g = vis.get(idx);
            rowGroups.add(g);
            hit("row" + i, cx + 6, top + 26 + i * rowH, cw - 12, rowH - 2, () -> {
                if (!Config.disabled.remove(g.id())) Config.disabled.add(g.id());
            });
        }
        hit("prev", cx + cw - 44, top, 18, 16, () -> { page = (page + pages - 1) % pages; init(); });
        hit("next", cx + cw - 24, top, 18, 16, () -> { page = (page + 1) % pages; init(); });
    }

    private void initSources() {
        srcRows = PackBuilder.candidates();
        rows = Math.max(1, (bottom - top - 28) / rowH);
        for (int i = 0; i < srcRows.size() && i < rows; i++) {
            String s = srcRows.get(i);
            hit("src" + i, cx + 6, top + 26 + i * rowH, cw - 12, rowH - 2, () -> {
                if (!Config.sources.remove(s)) Config.sources.add(s);
            });
        }
    }

    private int gridCellH() { return Math.max(22, Math.min(46, (bottom - top - 16 - 3 * 18 - 14) / 3 - 4)); }

    private void initSettings() {
        int cellH = gridCellH(), gw = (cw - 5 * 6) / 4;
        for (int i = 0; i < Theme.ALL.size(); i++) {
            final Theme t = Theme.ALL.get(i);
            int x = cx + 6 + (i % 4) * (gw + 6), y = top + 16 + (i / 4) * (cellH + 4);
            hit("th" + i, x, y, gw, cellH, () -> { Config.theme = t.id(); theme = t; });
        }
        int oy = top + 16 + 3 * (cellH + 4) + 6;
        hit("opt0", cx + 6, oy, cw - 12, 16, () -> Config.anim = !Config.anim);
        hit("opt1", cx + 6, oy + 18, cw - 12, 16, () -> Config.intensity = (Config.intensity + 1) % 3);
        hit("opt2", cx + 6, oy + 36, cw - 12, 16, () -> Config.glow = !Config.glow);
    }

    // ---------------- actions ----------------
    private void apply() {
        if (Config.sources.isEmpty()) { status = Component.literal("Pick a pack in Sources first"); return; }
        Config.save();
        try {
            if (PackBuilder.buildAll()) {
                status = Component.literal("Applied to " + Config.sources.size() + " pack(s)");
                PackBuilder.selectToggled(Minecraft.getInstance());
            } else {
                status = Component.literal("A pack is in use - restart the game to apply");
            }
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Build failed", e);
            status = Component.literal("Failed: " + e.getMessage());
        }
    }

    // ---------------- drawing ----------------
    private PlayerSkin skin() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getSkin() : DefaultPlayerSkin.get(mc.getUser().getProfileId());
    }

    private void icon(GuiGraphics g, int i, int x, int y, int c, int bgc) {
        switch (i) {
            case 0 -> { g.fill(x + 1, y + 8, x + 4, y + 14, c); g.fill(x + 6, y + 4, x + 9, y + 14, c); g.fill(x + 11, y, x + 14, y + 14, c); }
            case 1 -> { g.fill(x, y, x + 6, y + 6, c); g.fill(x + 8, y, x + 14, y + 6, c); g.fill(x, y + 8, x + 6, y + 14, c); g.fill(x + 8, y + 8, x + 14, y + 14, c); }
            case 2 -> { g.fill(x, y + 2, x + 6, y + 4, c); g.fill(x, y + 4, x + 14, y + 13, c); g.fill(x + 2, y + 6, x + 12, y + 7, bgc); }
            default -> { g.fill(x + 6, y, x + 8, y + 14, c); g.fill(x, y + 6, x + 14, y + 8, c); disc(g, x + 7, y + 7, 5, c); disc(g, x + 7, y + 7, 2, bgc); }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        long now = System.nanoTime();
        dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;
        openT = Math.min(1f, openT + dt * 3f);
        tabT = Math.min(1f, tabT + dt * 4f);

        if (!colorsInit) { cAccent = theme.accent(); cAccent2 = theme.accent2(); cBg = theme.bg(); cCard = theme.card(); cLine = theme.line(); colorsInit = true; }
        float k = Math.min(1f, dt * 6f);
        cAccent = mix(cAccent, theme.accent(), k); cAccent2 = mix(cAccent2, theme.accent2(), k);
        cBg = mix(cBg, theme.bg(), k); cCard = mix(cCard, theme.card(), k); cLine = mix(cLine, theme.line(), k);

        super.render(g, mx, my, partial); // vanilla backdrop + transparent click areas

        // glow border
        float pulse = Config.glow ? 0.5f + 0.5f * (float) Math.sin(time * 1.6f) : 0.5f;
        for (int i = 5; i >= 1; i--) round(g, px - i, py - i, pw + 2 * i, ph + 2 * i, alpha(cAccent, (0.10f + 0.10f * pulse) / i));
        round(g, px, py, pw, ph, mix(cAccent, cBg, 0.45f));
        round(g, px + 1, py + 1, pw - 2, ph - 2, cBg);

        // animated scene behind the content
        g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
        if (Config.anim) {
            fx.update(dt, theme, pw, ph, Config.intensity);
            fx.draw(g, font, theme, px, py, pw, ph);
        }
        // moving accent line along the top edge
        int seg = 90, sx = px + (int) ((time * 70) % (pw + seg)) - seg;
        for (int i = 0; i < seg; i += 2) {
            int xx = sx + i;
            if (xx > px && xx < px + pw) g.fill(xx, py + 1, xx + 2, py + 2, alpha(cAccent2, (i / (float) seg) * 0.9f));
        }
        g.disableScissor();

        drawSidebar(g);
        drawHeader(g);

        // content (fades in on tab switch)
        g.enableScissor(cx - 2, top - 2, cx + cw + 2, bottom + 2);
        switch (tab) {
            case 1 -> drawPacks(g);
            case 2 -> drawSources(g);
            case 3 -> drawSettings(g);
            default -> drawOverview(g);
        }
        float fade = 1f - tabT;
        if (fade > 0.01f) g.fill(cx - 2, top - 2, cx + cw + 2, bottom + 2, alpha(cBg, fade * fade));
        g.disableScissor();

        drawBottom(g);

        // open animation: panel fades up from dark
        float o = 1f - openT;
        if (o > 0.01f) round(g, px, py, pw, ph, alpha(0x000000, o * o));
    }

    private void drawSidebar(GuiGraphics g) {
        round(g, px + 6, py + 6, 38, ph - 12, mix(cCard, cBg, 0.3f));
        disc(g, px + 25, py + 23, 11, alpha(cAccent, 0.25f));
        disc(g, px + 25, py + 23, 8, cAccent);
        disc(g, px + 25, py + 23, 4, cAccent2);
        for (int i = 0; i < TABS.length; i++) {
            float sel = ease("tabsel" + i, tab == i ? 1 : 0, 14), hv = ease("tabhov" + i, hov("tab" + i) ? 1 : 0, 16);
            int bx = px + 8, by = py + 44 + i * 34;
            if (hv > 0.02f) round(g, bx, by, 34, 28, alpha(cAccent, 0.12f * hv));
            if (sel > 0.02f) { round(g, bx, by, 34, 28, alpha(cAccent, 0.95f * sel)); g.fill(px + 6, by + 6, px + 8, by + 6 + (int) (16 * sel), cAccent2); }
            icon(g, i, bx + 10, by + 7, mix(TEXT, cBg, sel), mix(cBg, cAccent, sel));
        }
    }

    private void drawHeader(GuiGraphics g) {
        bold(g, TABS[tab], cx, py + 12, TEXT);
        // skin + name
        String name = Minecraft.getInstance().getUser().getName();
        int nameW = font.width(name);
        int closeX = px + pw - 26;
        int pillW = nameW + 16 + 22;
        int pillX = closeX - 8 - pillW;
        round(g, pillX, py + 8, pillW, 20, mix(cCard, cBg, 0.2f));
        try { PlayerFaceRenderer.draw(g, skin(), pillX + 3, py + 10, 16); } catch (Exception ignored) { }
        text(g, name, pillX + 24, py + 14, TEXT);
        String src = "Sources: " + Config.sources.size();
        text(g, src, pillX - 8 - font.width(src), py + 14, muted());
        boolean hc = hov("close");
        round(g, closeX, py + 10, 16, 16, hc ? RED : cCard);
        text(g, "x", closeX + 5, py + 14, TEXT);
    }

    private void drawBottom(GuiGraphics g) {
        text(g, fit(status.getString(), cw - applyW - 16), cx + 2, applyY + 8, muted());
        float h = ease("applyhov", hov("apply") ? 1 : 0, 14);
        round(g, applyX - 1, applyY - 1, applyW + 2, applyH + 2, alpha(cAccent2, 0.25f * h));
        round(g, applyX, applyY, applyW, applyH, mix(cAccent, cAccent2, h * 0.5f));
        String a = "Apply & reload";
        bold(g, a, applyX + (applyW - font.width(a) - 1) / 2, applyY + 8, cBg);
    }

    private void drawOverview(GuiGraphics g) {
        int total = Group.ALL.size(), off = 0;
        for (Group gr : Group.ALL) if (Config.disabled.contains(gr.id())) off++;
        int on = total - off, gap = 8, h = 52;
        int pw1 = 170, w3 = (cw - pw1 - 3 * gap) / 3;

        card(g, cx, top, pw1, h);
        try { PlayerFaceRenderer.draw(g, skin(), cx + 8, top + 8, 36); } catch (Exception ignored) { }
        String name = Minecraft.getInstance().getUser().getName();
        bold(g, fit(name, pw1 - 56), cx + 50, top + 12, TEXT);
        text(g, fit(theme.name() + " theme", pw1 - 56), cx + 50, top + 26, muted());
        text(g, "Pack Toggles", cx + 50, top + 38, cAccent);

        int x = cx + pw1 + gap;
        String[] lbl = {"Enabled", "Disabled", "Packs"};
        float[] vals = {on, off, Config.sources.size()};
        int[] cols = {GREEN, off == 0 ? TEXT : RED, cAccent2};
        for (int i = 0; i < 3; i++) {
            card(g, x, top, w3, h);
            text(g, lbl[i], x + 8, top + 8, muted());
            float v = ease("stat" + i, vals[i], 8);
            bold(g, String.valueOf(Math.round(v)), x + 8, top + 22, cols[i]);
            if (i == 0) {
                int bw = w3 - 16;
                g.fill(x + 8, top + 38, x + 8 + bw, top + 42, mix(cCard, cBg, 0.6f));
                float f = ease("bar", on / (float) Math.max(1, total), 6);
                g.fill(x + 8, top + 38, x + 8 + (int) (bw * f), top + 42, cAccent);
            }
            x += w3 + gap;
        }

        int y2 = top + h + gap, h2 = bottom - y2;
        card(g, cx, y2, cw, h2);
        bold(g, off == 0 ? "Everything is enabled" : "Turned off", cx + 10, y2 + 8, TEXT);
        int chipX = cx + 10, chipY = y2 + 26;
        for (Group gr : Group.ALL) {
            if (!Config.disabled.contains(gr.id())) continue;
            int w = font.width(gr.label()) + 12;
            if (chipX + w > cx + cw - 10) { chipX = cx + 10; chipY += 20; }
            if (chipY + 16 > y2 + h2) break;
            round(g, chipX, chipY, w, 16, alpha(RED, 0.25f));
            text(g, gr.label(), chipX + 6, chipY + 4, TEXT);
            chipX += w + 4;
        }
        if (off == 0) text(g, "Open the Packs tab to switch parts of your resource packs off.", cx + 10, y2 + 26, muted());
    }

    private void drawPacks(GuiGraphics g) {
        int chipX = cx + 8;
        for (int i = 0; i < CHIPS.length; i++) {
            int w = font.width(CHIPS[i]) + 14;
            float sel = ease("chip" + i, chip == i ? 1 : 0, 14);
            round(g, chipX, top, w, 16, mix(cCard, cAccent, 0.15f + 0.8f * sel));
            text(g, CHIPS[i], chipX + 7, top + 4, mix(TEXT, cBg, sel));
            chipX += w + 4;
        }
        List<Group> vis = visible();
        int pages = Math.max(1, (vis.size() + rows - 1) / rows);
        round(g, cx + cw - 128, top, 36, 16, hov("allon") ? mix(cCard, GREEN, 0.5f) : cCard);
        text(g, "All on", cx + cw - 124, top + 4, TEXT);
        round(g, cx + cw - 88, top, 38, 16, hov("alloff") ? mix(cCard, RED, 0.5f) : cCard);
        text(g, "All off", cx + cw - 84, top + 4, TEXT);
        round(g, cx + cw - 44, top, 18, 16, hov("prev") ? mix(cCard, cAccent, 0.5f) : cCard);
        text(g, "<", cx + cw - 38, top + 4, TEXT);
        round(g, cx + cw - 24, top, 18, 16, hov("next") ? mix(cCard, cAccent, 0.5f) : cCard);
        text(g, ">", cx + cw - 18, top + 4, TEXT);
        String pg = (page + 1) + "/" + pages;
        text(g, pg, cx + cw - 132 - font.width(pg), top + 4, muted());

        card(g, cx, top + 20, cw, bottom - top - 20);
        for (int i = 0; i < rowGroups.size(); i++) {
            Group gr = rowGroups.get(i);
            boolean enabled = !Config.disabled.contains(gr.id());
            int ry = top + 26 + i * rowH;
            float hv = ease("rowhov" + gr.id(), hov("row" + i) ? 1 : 0, 14);
            if (hv > 0.02f) round(g, cx + 6, ry, cw - 12, rowH - 2, alpha(cAccent, 0.12f * hv));
            float sw = ease("sw" + gr.id(), enabled ? 1 : 0, 16);
            text(g, fit(gr.label(), cw - 90), cx + 14 + (int) (2 * hv), ry + 6, mix(muted(), TEXT, sw));
            int sx = cx + cw - 14 - 26, sy = ry + 4;
            round(g, sx, sy, 26, 12, mix(mix(cCard, TEXT, 0.15f), cAccent, sw));
            round(g, sx + 1 + (int) (14 * sw), sy + 1, 10, 10, 0xFFFFFFFF);
        }
    }

    private void drawSources(GuiGraphics g) {
        text(g, "Tick the packs the toggles should apply to. Packs that are on in game are marked ON.", cx + 2, top + 2, muted());
        card(g, cx, top + 16, cw, bottom - top - 16);
        Set<String> enabled;
        try { enabled = PackBuilder.enabledInGame(); } catch (Exception e) { enabled = Set.of(); }
        if (srcRows.isEmpty()) text(g, "No .zip packs or pack folders found in resourcepacks/", cx + 14, top + 30, muted());
        for (int i = 0; i < srcRows.size() && i < rows; i++) {
            String s = srcRows.get(i);
            boolean sel = Config.sources.contains(s);
            int ry = top + 26 + i * rowH;
            float hv = ease("srchov" + i, hov("src" + i) ? 1 : 0, 14);
            if (hv > 0.02f) round(g, cx + 6, ry, cw - 12, rowH - 2, alpha(cAccent, 0.12f * hv));
            float sw = ease("srcsel" + s, sel ? 1 : 0, 16);
            round(g, cx + 12, ry + 4, 12, 12, mix(mix(cCard, TEXT, 0.15f), cAccent, sw));
            if (sw > 0.5f) { g.fill(cx + 15, ry + 9, cx + 17, ry + 11, cBg); g.fill(cx + 17, ry + 11, cx + 21, ry + 12, cBg); g.fill(cx + 19, ry + 8, cx + 21, ry + 11, cBg); }
            text(g, fit(s, cw - 120), cx + 32, ry + 6, TEXT);
            if (enabled.contains(s)) {
                round(g, cx + cw - 46, ry + 4, 30, 12, alpha(GREEN, 0.3f));
                text(g, "ON", cx + cw - 38, ry + 6, GREEN);
            }
        }
    }

    private void drawSettings(GuiGraphics g) {
        text(g, "Theme", cx + 4, top + 2, muted());
        int cellH = gridCellH(), gw = (cw - 5 * 6) / 4;
        for (int i = 0; i < Theme.ALL.size(); i++) {
            Theme t = Theme.ALL.get(i);
            int x = cx + 6 + (i % 4) * (gw + 6), y = top + 16 + (i / 4) * (cellH + 4);
            boolean sel = t.id().equals(Config.theme);
            float hv = ease("thhov" + i, hov("th" + i) ? 1 : 0, 14), sv = ease("thsel" + i, sel ? 1 : 0, 12);
            round(g, x - 1, y - 1, gw + 2, cellH + 2, mix(t.line(), t.accent(), 0.25f * hv + 0.85f * sv));
            round(g, x, y, gw, cellH, t.card());
            bold(g, fit(t.name(), gw - 30), x + 6, y + 5, TEXT);
            if (cellH >= 34) text(g, fit(t.sub(), gw - 12), x + 6, y + 16, mix(TEXT, t.card(), 0.5f));
            g.fill(x + 6, y + cellH - 5, x + gw - 6, y + cellH - 3, t.accent());
            g.fill(x + gw / 2, y + cellH - 5, x + gw - 6, y + cellH - 3, t.accent2());
            disc(g, x + gw - 10, y + 8, 2, t.accent()); disc(g, x + gw - 18, y + 8, 2, t.accent2());
            if (sv > 0.5f) { disc(g, x + gw - 10, y + cellH - 11, 5, t.accent()); g.fill(x + gw - 13, y + cellH - 11, x + gw - 11, y + cellH - 9, t.bg()); g.fill(x + gw - 11, y + cellH - 9, x + gw - 7, y + cellH - 8, t.bg()); }
        }
        int oy = top + 16 + 3 * (cellH + 4) + 6;
        optionRow(g, 0, oy, "Animations", null, Config.anim);
        optionRow(g, 1, oy + 18, "Particle amount", INTENSITY[Config.intensity], true);
        optionRow(g, 2, oy + 36, "Glow pulse", null, Config.glow);
    }

    private void optionRow(GuiGraphics g, int i, int y, String label, String value, boolean on) {
        float hv = ease("opthov" + i, hov("opt" + i) ? 1 : 0, 14);
        round(g, cx + 6, y, cw - 12, 16, alpha(cAccent, 0.06f + 0.12f * hv));
        text(g, label, cx + 14, y + 4, TEXT);
        if (value != null) {
            text(g, value, cx + cw - 14 - font.width(value), y + 4, cAccent2);
        } else {
            float sw = ease("optsw" + i, on ? 1 : 0, 16);
            int sx = cx + cw - 14 - 26, sy = y + 2;
            round(g, sx, sy, 26, 12, mix(mix(cCard, TEXT, 0.15f), cAccent, sw));
            round(g, sx + 1 + (int) (14 * sw), sy + 1, 10, 10, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        Config.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
