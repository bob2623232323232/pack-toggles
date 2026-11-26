package dev.packtoggles;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Themed dashboard. Everything is drawn by hand in render(); the vanilla Buttons are fully
 * transparent and only provide the click areas (and hover state).
 *
 * New in 1.1.0 (Pyra Insights style):
 *  - five navigation layouts: Classic, Rail, Tabs, Dock, Radial (press L anywhere to cycle)
 *  - search box in the header (Ctrl+K), filters the Packs tab
 *  - number keys 1-5 switch tabs, hover tooltips on icon-only navigation
 *  - status pill (Up to date / Changes pending / Restart to apply)
 *  - Settings split into Layout / Theme / Display pages with card sections
 */
public class ToggleScreen extends Screen {
    private static final int TEXT = 0xFFF5E8DD, GREEN = 0xFF58D26F, RED = 0xFFE0564F, AMBER = 0xFFF0B429;
    private static final String[] TABS = {"Overview", "Packs", "Trims", "Sources", "Settings"};
    private static final int T_OVERVIEW = 0, T_PACKS = 1, T_TRIMS = 2, T_SOURCES = 3, T_SETTINGS = 4;
    private static final String[] CHIPS = {"All", "Minecraft", "Mods", "Misc"};
    private static final String[] INTENSITY = {"Low", "Medium", "High"};
    private static final String[] SET_CHIPS = {"Layout", "Theme", "Display"};
    private static final Set<String> MC_IDS = Set.of("blocks", "items", "gui", "trims", "entities", "effects", "particles", "ranks", "misc_tex");
    private static final Set<String> MISC_IDS = Set.of("sounds", "shaders", "optifine", "lang", "respackopts",
            "carl", "potions_icons", "visible_cooldown", "entity_features");

    // layouts
    private static final int L_CLASSIC = 0, L_RAIL = 1, L_TABS = 2, L_DOCK = 3, L_RADIAL = 4;
    private static final String[] LAYOUTS = {"Classic", "Rail", "Tabs", "Dock", "Radial"};
    private static final String[] LAYOUT_SUB = {"Sidebar with labels", "Slim icon rail", "Pill tabs across the top",
            "Magnifying dock at the bottom", "Wheel menu, Tab to open"};
    private static int layout = L_RAIL;                  // remembered while the game is running
    private static Set<String> appliedD, appliedS;        // what the last Apply built (for the status pill)

    // armor trim showcase data
    private static final String[] PATTERNS = {"Sentry", "Dune", "Coast", "Wild", "Ward", "Eye", "Vex", "Tide", "Snout",
            "Rib", "Spire", "Wayfinder", "Shaper", "Silence", "Raiser", "Host", "Flow", "Bolt"};
    private static final String[] MATS = {"Quartz", "Iron", "Netherite", "Redstone", "Copper", "Gold",
            "Emerald", "Diamond", "Lapis", "Amethyst", "Resin"};
    private static final int[] MAT_COL = {0xFFE3D4C4, 0xFFCECACA, 0xFF625859, 0xFF971607, 0xFFB4684D, 0xFFDEB12D,
            0xFF11A036, 0xFF6EECD2, 0xFF416E97, 0xFF9A5CC6, 0xFFFC7812};
    // 12 columns wide: X = armor, T = trim, . = empty
    private static final String[] HELM = {
            "..XXXXXXXX..",
            ".XXXXXXXXXX.",
            "XXXXXXXXXXXX",
            "XTTTTTTTTTTX",
            "XTTTTTTTTTTX",
            "XXXXXXXXXXXX",
            "XXX......XXX",
            "XX........XX",
            "XX........XX"};

    private final Screen parent;
    private final FX fx = new FX();
    private final Map<String, Float> eased = new HashMap<>();
    private final Map<String, Button> hits = new HashMap<>();
    private int tab = 0, chip = 0, page = 0, setChip = 0;
    private float openT = 0, tabT = 1;
    private int tabDir = 1;                 // +1: content enters from the right, -1: from the left
    private float selX = Float.NaN, selY = 0, selVX = 0, selVY = 0; // navigation highlight spring
    private long lastNanos = System.nanoTime();
    private float dt = 0.016f, time = 0;
    private Component status = Component.empty();
    private Theme theme = Theme.byId(Config.theme);
    private int cAccent, cAccent2, cBg, cCard, cLine; // eased theme colours
    private boolean colorsInit = false;

    // search
    private EditBox search;
    private String searchText = "";
    private boolean searchActive = false, building = false, dirty = false, refocus = false;
    private boolean radialOpen = false, restartNeeded = false;

    // layout
    private int px, py, pw, ph, cx, cw, top, bottom, rowH = 22, rows;
    private int tabStep = 34, tabH = 28;
    private int applyX, applyY, applyW = 150, applyH = 24;
    private int closeX, nameX, nameW, stX, stW, searchX, searchW;
    private List<Group> rowGroups = new ArrayList<>();
    private List<String> srcRows = new ArrayList<>();

    public ToggleScreen(Screen parent) {
        super(Component.translatable("screen.packtoggles.title"));
        this.parent = parent;
        PackTogglesClient.LOG.info("[Pack Toggles] screen opened: build 1.1.0, {} tabs, layout={}, anim={}, glow={}", TABS.length, LAYOUTS[layout], Config.anim, Config.glow);
        if (Config.sources.isEmpty()) {
            try { Config.sources.addAll(PackBuilder.enabledInGame()); } catch (Exception ignored) { }
            if (Config.sources.isEmpty()) {
                List<String> c = PackBuilder.candidates();
                if (!c.isEmpty()) Config.sources.add(c.get(0));
            }
        }
        if (appliedD == null) { // first open this session: assume the current config is what is applied
            appliedD = new HashSet<>(Config.disabled);
            appliedS = new HashSet<>(Config.sources);
        }
    }

    // ---------------- helpers ----------------
    private float ease(String key, float target, float speed) {
        float v = eased.getOrDefault(key, target);
        v += (target - v) * Math.min(1f, dt * speed);
        eased.put(key, v);
        return v;
    }

    /** ease-out cubic, input clamped to 0..1 */
    private static float eo(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Entrance progress (0..1) of the i-th item in a cascade; restarts whenever tabT is reset to 0. */
    private float stag(int i) { return eo((tabT - Math.min(i, 12) * 0.04f) / 0.55f); }

    /** Restart the slide/cascade entrance (dir = side the content comes from). */
    private void restartEntrance(int dir) { tabT = 0; tabDir = dir; }

    private static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static int alpha(int rgb, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int lum(int c) { return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 1000; }

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

    private void toggleSwitch(GuiGraphics g, int x, int y, float sw) {
        round(g, x, y, 26, 12, mix(mix(cCard, TEXT, 0.15f), cAccent, sw));
        round(g, x + 1 + (int) (14 * sw), y + 1, 10, 10, 0xFFFFFFFF);
    }

    private boolean hov(String key) { Button b = hits.get(key); return b != null && b.isHoveredOrFocused(); }

    private void hit(String key, int x, int y, int w, int h, Runnable r) {
        Button b = Button.builder(Component.empty(), btn -> r.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f); // invisible but clickable
        addRenderableWidget(b);
        hits.put(key, b);
    }

    private void setTab(int t) {
        radialOpen = false;
        if (t == tab) { init(); return; }
        restartEntrance(t > tab ? 1 : -1);
        PackTogglesClient.LOG.info("[Pack Toggles] tab -> {}", TABS[t]);
        tab = t;
        page = 0;
        init();
    }

    private void setLayout(int l) {
        layout = ((l % LAYOUTS.length) + LAYOUTS.length) % LAYOUTS.length;
        radialOpen = false;
        selX = Float.NaN;
        restartEntrance(1);
        PackTogglesClient.LOG.info("[Pack Toggles] layout -> {}", LAYOUTS[layout]);
        init();
    }

    private boolean pending() {
        return !new HashSet<>(Config.disabled).equals(appliedD) || !new HashSet<>(Config.sources).equals(appliedS);
    }

    private String pillLabel() { return restartNeeded ? "Restart to apply" : pending() ? "Changes pending" : "Up to date"; }

    private int pillColor() { return restartNeeded ? RED : pending() ? AMBER : GREEN; }

    // ---------------- navigation geometry ----------------
    private int[] navRect(int i) {
        switch (layout) {
            case L_CLASSIC:
                return new int[]{px + 8, py + 44 + i * tabStep, 104, tabH};
            case L_TABS: {
                int x = px + 10;
                for (int k = 0; k < i; k++) x += font.width(TABS[k]) + 16 + 4;
                return new int[]{x, py + 34, font.width(TABS[i]) + 16, 20};
            }
            default:
                return new int[]{px + 8, py + 44 + i * tabStep, 34, tabH};
        }
    }

    private int dockX(int i) { return px + (pw - (TABS.length * 38 - 8)) / 2 + i * 38; }

    private int dockY() { return py + ph - 8 - 30; }

    private int[] wheelPos(int i) {
        int ccx = cx + cw / 2, ccy = top + (bottom - top) / 2;
        int r = Math.max(30, Math.min(70, (bottom - top) / 2 - 26));
        double a = -Math.PI / 2 + i * 2 * Math.PI / TABS.length;
        return new int[]{ccx + (int) Math.round(r * Math.cos(a)), ccy + (int) Math.round(r * Math.sin(a))};
    }

    // ---------------- layout / click areas ----------------
    @Override
    protected void init() {
        clearWidgets();
        hits.clear();
        search = null;
        searchActive = false;
        pw = Math.min(width - 24, 560);
        ph = Math.min(height - 16, 340);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        switch (layout) {
            case L_CLASSIC -> { cx = px + 120; cw = pw - 128; top = py + 34; }
            case L_TABS -> { cx = px + 10; cw = pw - 20; top = py + 60; }
            case L_RAIL -> { cx = px + 52; cw = pw - 60; top = py + 34; }
            default -> { cx = px + 10; cw = pw - 20; top = py + 34; }
        }
        applyY = py + ph - 8 - applyH - (layout == L_DOCK ? 38 : 0);
        applyX = cx + cw - applyW;
        bottom = applyY - 8;

        tabStep = Math.max(22, Math.min(34, (ph - 44 - 12 - (layout == L_CLASSIC ? 44 : 0)) / TABS.length));
        tabH = Math.min(28, tabStep - 4);

        // navigation click areas
        if (layout == L_DOCK) {
            for (int i = 0; i < TABS.length; i++) {
                final int idx = i;
                hit("tab" + i, dockX(i), dockY(), 30, 30, () -> setTab(idx));
            }
        } else if (layout == L_RADIAL) {
            hit("menu", px + 8, py + 8, 20, 20, () -> { radialOpen = !radialOpen; init(); });
            if (radialOpen) {
                for (int i = 0; i < TABS.length; i++) {
                    final int idx = i;
                    int[] p = wheelPos(i);
                    hit("tab" + i, p[0] - 17, p[1] - 17, 34, 34, () -> setTab(idx));
                }
            }
        } else {
            for (int i = 0; i < TABS.length; i++) {
                final int idx = i;
                int[] r = navRect(i);
                hit("tab" + i, r[0], r[1], r[2], r[3], () -> setTab(idx));
            }
        }

        // header geometry: [title] ... [search] [status pill] [name pill] [x]
        closeX = px + pw - 26;
        String name = Minecraft.getInstance().getUser().getName();
        nameW = font.width(name) + 16 + 22;
        nameX = closeX - 8 - nameW;
        stW = Math.max(font.width("Changes pending"), Math.max(font.width("Up to date"), font.width("Restart to apply"))) + 26;
        stX = nameX - 8 - stW;
        searchW = Math.min(130, stX - 8 - (cx + (layout == L_RADIAL ? 26 : 0) + 96));
        searchX = stX - 8 - searchW;
        searchActive = searchW >= 70;
        if (searchActive) {
            search = new EditBox(font, searchX + 20, py + 13, searchW - 26, 12, Component.literal("Search"));
            search.setBordered(false);
            search.setMaxLength(32);
            search.setHint(Component.literal("Search  Ctrl K").withStyle(ChatFormatting.DARK_GRAY));
            building = true;
            search.setValue(searchText);
            building = false;
            search.setResponder(s -> {
                if (building) return;
                searchText = s;
                page = 0;
                if (tab != T_PACKS) { tab = T_PACKS; restartEntrance(1); }
                dirty = true;
                refocus = true;
            });
            addWidget(search); // drawn manually in drawHeader so it sits on top of the panel
        }

        hit("close", closeX, py + 10, 16, 16, this::onClose);
        hit("apply", applyX, applyY, applyW, applyH, this::apply);

        rowGroups = new ArrayList<>();
        srcRows = new ArrayList<>();
        if (layout == L_RADIAL && radialOpen) return; // wheel covers the content
        switch (tab) {
            case T_PACKS -> initPacks();
            case T_TRIMS -> initTrims();
            case T_SOURCES -> initSources();
            case T_SETTINGS -> initSettings();
            default -> { }
        }
    }

    private List<Group> visible() {
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        List<Group> out = new ArrayList<>();
        for (Group g : Group.ALL) {
            boolean mc = MC_IDS.contains(g.id()), misc = MISC_IDS.contains(g.id());
            boolean inChip = chip == 0 || (chip == 1 && mc) || (chip == 2 && !mc && !misc) || (chip == 3 && misc);
            boolean match = q.isEmpty() || g.label().toLowerCase(Locale.ROOT).contains(q) || g.id().toLowerCase(Locale.ROOT).contains(q);
            if (inChip && match) out.add(g);
        }
        return out;
    }

    private void initPacks() {
        int chipX = cx + 8;
        for (int i = 0; i < CHIPS.length; i++) {
            final int idx = i;
            int w = font.width(CHIPS[i]) + 14;
            hit("chip" + i, chipX, top, w, 16, () -> { chip = idx; page = 0; restartEntrance(1); init(); });
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
        hit("prev", cx + cw - 44, top, 18, 16, () -> { page = (page + pages - 1) % pages; restartEntrance(-1); init(); });
        hit("next", cx + cw - 24, top, 18, 16, () -> { page = (page + 1) % pages; restartEntrance(1); init(); });
    }

    private int trimLeftW() { return Math.max(90, Math.min(130, cw / 3)); }

    private void initTrims() {
        int x0 = cx + trimLeftW() + 8, w = cw - trimLeftW() - 8;
        hit("trimsw", x0, top, w, 30, () -> { if (!Config.disabled.remove("trims")) Config.disabled.add("trims"); });
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

    // ---- settings pages ----
    private int layCardW() { return (cw - 12 - 4 * 6) / 5; }

    private int layCardH() { return Math.max(56, Math.min(96, bottom - top - 22 - 6)); }

    private int themeRows() { return Math.max(1, (Theme.ALL.size() + 3) / 4); }

    private int themeCellH() {
        int r = themeRows();
        return Math.max(20, Math.min(46, (bottom - top - 26 - (r - 1) * 4) / r));
    }

    private int dispCardW() { return (cw - 8) / 2; }

    private void initSettings() {
        int chipX = cx + 8;
        for (int i = 0; i < SET_CHIPS.length; i++) {
            final int idx = i;
            int w = font.width(SET_CHIPS[i]) + 14;
            hit("schip" + i, chipX, top, w, 16, () -> { setChip = idx; restartEntrance(1); init(); });
            chipX += w + 4;
        }
        int y0 = top + 22;
        if (setChip == 0) {
            int gw = layCardW(), gh = layCardH();
            for (int i = 0; i < LAYOUTS.length; i++) {
                final int idx = i;
                hit("lay" + i, cx + 6 + i * (gw + 6), y0, gw, gh, () -> setLayout(idx));
            }
        } else if (setChip == 1) {
            int cellH = themeCellH(), gw = (cw - 5 * 6) / 4;
            for (int i = 0; i < Theme.ALL.size(); i++) {
                final Theme t = Theme.ALL.get(i);
                int x = cx + 6 + (i % 4) * (gw + 6), y = y0 + (i / 4) * (cellH + 4);
                hit("th" + i, x, y, gw, cellH, () -> { Config.theme = t.id(); theme = t; });
            }
        } else {
            int dw = dispCardW();
            hit("opt0", cx + 6, y0 + 22, dw - 12, 16, () -> Config.anim = !Config.anim);
            hit("opt1", cx + 6, y0 + 40, dw - 12, 16, () -> Config.intensity = (Config.intensity + 1) % 3);
            hit("opt2", cx + 6, y0 + 58, dw - 12, 16, () -> Config.glow = !Config.glow);
            int rx = cx + dw + 8, bw = (dw - 24) / 2;
            hit("allon2", rx + 8, y0 + 48, bw, 18, () -> { for (Group g : Group.ALL) Config.disabled.remove(g.id()); });
            hit("alloff2", rx + 16 + bw, y0 + 48, bw, 18, () -> { for (Group g : Group.ALL) Config.disabled.add(g.id()); });
        }
    }

    // ---------------- actions ----------------
    private void apply() {
        if (Config.sources.isEmpty()) { status = Component.literal("Pick a pack in Sources first"); return; }
        Config.save();
        try {
            if (PackBuilder.buildAll()) {
                status = Component.literal("Applied to " + Config.sources.size() + " pack(s)");
                appliedD = new HashSet<>(Config.disabled);
                appliedS = new HashSet<>(Config.sources);
                restartNeeded = false;
                PackBuilder.selectToggled(Minecraft.getInstance());
            } else {
                restartNeeded = true;
                status = Component.literal("A pack is in use - restart the game to apply");
            }
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Build failed", e);
            status = Component.literal("Failed: " + e.getMessage());
        }
    }

    // ---------------- input ----------------
    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        boolean ctrl = (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean typing = search != null && search.isFocused();
        if (ctrl && k == GLFW.GLFW_KEY_K && search != null) {
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (typing) {
            if (k == GLFW.GLFW_KEY_ESCAPE) { search.setFocused(false); setFocused(null); return true; }
            return super.keyPressed(e);
        }
        if (k >= GLFW.GLFW_KEY_1 && k < GLFW.GLFW_KEY_1 + TABS.length) { setTab(k - GLFW.GLFW_KEY_1); return true; }
        if (k == GLFW.GLFW_KEY_L) { setLayout(layout + 1); return true; }
        if (k == GLFW.GLFW_KEY_TAB && layout == L_RADIAL) { radialOpen = !radialOpen; init(); return true; }
        return super.keyPressed(e);
    }

    // ---------------- drawing ----------------
    private PlayerSkin skin() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getSkin() : DefaultPlayerSkin.get(mc.getUser().getProfileId());
    }

    private void icon(GuiGraphics g, int i, int x, int y, int c, int bgc) {
        switch (i) {
            case T_OVERVIEW -> { g.fill(x + 1, y + 8, x + 4, y + 14, c); g.fill(x + 6, y + 4, x + 9, y + 14, c); g.fill(x + 11, y, x + 14, y + 14, c); }
            case T_PACKS -> { g.fill(x, y, x + 6, y + 6, c); g.fill(x + 8, y, x + 14, y + 6, c); g.fill(x, y + 8, x + 6, y + 14, c); g.fill(x + 8, y + 8, x + 14, y + 14, c); }
            case T_TRIMS -> { // little helmet with a trim band
                g.fill(x + 2, y, x + 12, y + 2, c); g.fill(x, y + 2, x + 14, y + 13, c);
                g.fill(x + 1, y + 5, x + 13, y + 7, bgc); g.fill(x + 3, y + 9, x + 11, y + 13, bgc);
            }
            case T_SOURCES -> { g.fill(x, y + 2, x + 6, y + 4, c); g.fill(x, y + 4, x + 14, y + 13, c); g.fill(x + 2, y + 6, x + 12, y + 7, bgc); }
            default -> { g.fill(x + 6, y, x + 8, y + 14, c); g.fill(x, y + 6, x + 14, y + 8, c); disc(g, x + 7, y + 7, 5, c); disc(g, x + 7, y + 7, 2, bgc); }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        if (dirty) { // search text changed: rebuild click areas, keep typing
            dirty = false;
            init();
            if (refocus && search != null) { setFocused(search); search.setFocused(true); }
            refocus = false;
        }
        long now = System.nanoTime();
        dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;
        openT = Math.min(1f, openT + dt * 3f);
        tabT = Math.min(1f, tabT + dt * 2.2f);

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

        drawNav(g);
        drawHeader(g, mx, my, partial);

        // content: slides in from the side + fades in on every tab / page / chip change
        float e = eo(tabT);
        int saveCx = cx;
        cx = saveCx + (int) ((1f - e) * 28f * tabDir); // draw methods read cx, so shifting it slides everything
        g.enableScissor(saveCx - 2, top - 2, saveCx + cw + 2, bottom + 2);
        try {
            switch (tab) {
                case T_PACKS -> drawPacks(g);
                case T_TRIMS -> drawTrims(g);
                case T_SOURCES -> drawSources(g);
                case T_SETTINGS -> drawSettings(g);
                default -> drawOverview(g);
            }
        } finally {
            cx = saveCx;
        }
        float fade = 1f - e;
        if (fade > 0.01f) g.fill(saveCx - 2, top - 2, saveCx + cw + 2, bottom + 2, alpha(cBg, fade * 0.9f));
        g.disableScissor();

        drawBottom(g);
        if (layout == L_RADIAL && radialOpen) drawWheel(g);
        drawTooltips(g);

        // open animation: panel fades up from dark
        float o = 1f - openT;
        if (o > 0.01f) round(g, px, py, pw, ph, alpha(0x000000, o * o));
    }

    // ---- navigation drawing ----
    private void drawNav(GuiGraphics g) {
        switch (layout) {
            case L_DOCK -> drawDock(g);
            case L_RADIAL -> drawRadialButton(g);
            case L_TABS -> drawTabsRow(g);
            default -> drawSidebar(g, layout == L_CLASSIC);
        }
    }

    /** Spring-driven highlight: glides to the selected item and overshoots a little. */
    private void springTo(int[] r) {
        if (Float.isNaN(selX)) { selX = r[0]; selY = r[1]; selVX = 0; selVY = 0; }
        for (int s = 0; s < 4; s++) {
            float h = dt / 4f;
            selVX += (320f * (r[0] - selX) - 26f * selVX) * h;
            selX += selVX * h;
            selVY += (320f * (r[1] - selY) - 26f * selVY) * h;
            selY += selVY * h;
        }
    }

    private void drawSidebar(GuiGraphics g, boolean classic) {
        int sbw = classic ? 106 : 38;
        round(g, px + 6, py + 6, sbw, ph - 12, mix(cCard, cBg, 0.3f));
        disc(g, px + 25, py + 23, 11, alpha(cAccent, 0.25f));
        disc(g, px + 25, py + 23, 8, cAccent);
        disc(g, px + 25, py + 23, 4, cAccent2);
        if (classic) bold(g, "Toggles", px + 42, py + 19, TEXT);

        int[] tr = navRect(tab);
        springTo(tr);
        int hx = Math.round(selX), hy = Math.round(selY);
        int hw = Math.round(ease("selw", tr[2], 14)), hh = Math.round(ease("selh", tr[3], 14));
        round(g, hx, hy, hw, hh, alpha(cAccent, 0.95f));
        g.fill(px + 6, hy + 6, px + 8, hy + hh - 6, cAccent2);

        for (int i = 0; i < TABS.length; i++) {
            int[] r = navRect(i);
            float hv = ease("tabhov" + i, hov("tab" + i) ? 1 : 0, 16);
            float sel = ease("tabsel" + i, tab == i ? 1 : 0, 14);
            if (hv > 0.02f && sel < 0.9f) round(g, r[0], r[1], r[2], r[3], alpha(cAccent, 0.12f * hv));
            icon(g, i, r[0] + 10, r[1] + (r[3] - 14) / 2, mix(TEXT, cBg, sel), mix(cBg, cAccent, sel));
            if (classic) text(g, TABS[i], r[0] + 30, r[1] + (r[3] - 8) / 2, mix(TEXT, cBg, sel));
        }

        if (classic) { // "wallet" card: how many groups are on
            int wy = py + ph - 50;
            if (wy > py + 44 + TABS.length * tabStep + 2) {
                int total = Group.ALL.size(), off = 0;
                for (Group gr : Group.ALL) if (Config.disabled.contains(gr.id())) off++;
                card(g, px + 10, wy, 98, 38);
                text(g, "Enabled", px + 17, wy + 7, muted());
                bold(g, (total - off) + " / " + total, px + 17, wy + 21, GREEN);
            }
        }
    }

    private void drawTabsRow(GuiGraphics g) {
        int[] tr = navRect(tab);
        springTo(tr);
        int hw = Math.round(ease("selw", tr[2], 14));
        round(g, Math.round(selX), Math.round(selY), hw, tr[3], alpha(cAccent, 0.95f));
        for (int i = 0; i < TABS.length; i++) {
            int[] r = navRect(i);
            float hv = ease("tabhov" + i, hov("tab" + i) ? 1 : 0, 16);
            float sel = ease("tabsel" + i, tab == i ? 1 : 0, 14);
            if (sel < 0.9f) round(g, r[0], r[1], r[2], r[3], alpha(cAccent, 0.06f + 0.14f * hv));
            if (sel > 0.5f) round(g, r[0], r[1], r[2], r[3], alpha(cAccent, 0.95f));
            text(g, TABS[i], r[0] + 8, r[1] + 6, mix(TEXT, cBg, sel));
        }
    }

    private void drawDock(GuiGraphics g) {
        int dockW = TABS.length * 38 - 8;
        round(g, dockX(0) - 10, dockY() - 8, dockW + 20, 46, mix(cCard, cBg, 0.3f));
        for (int i = 0; i < TABS.length; i++) {
            float hv = ease("dock" + i, hov("tab" + i) ? 1 : 0, 16);
            float sel = ease("tabsel" + i, tab == i ? 1 : 0, 14);
            int grow = Math.round(8 * hv);
            int x = dockX(i) - grow / 2, y = dockY() - grow, w = 30 + grow;
            round(g, x, y, w, w, mix(mix(cCard, TEXT, 0.06f), cAccent, 0.15f + 0.8f * sel));
            icon(g, i, x + (w - 14) / 2, y + (w - 14) / 2, mix(TEXT, cBg, sel), mix(mix(cCard, TEXT, 0.06f), cAccent, 0.15f + 0.8f * sel));
        }
    }

    private void drawRadialButton(GuiGraphics g) {
        float hv = ease("menuhov", hov("menu") ? 1 : 0, 14);
        round(g, px + 8, py + 8, 20, 20, mix(cCard, cAccent, 0.25f + 0.5f * hv + (radialOpen ? 0.3f : 0f)));
        disc(g, px + 18, py + 18, 5, cBg);
        disc(g, px + 18, py + 18, 3, cAccent2);
    }

    private void drawWheel(GuiGraphics g) {
        g.fill(cx - 2, top - 2, cx + cw + 2, bottom + 2, alpha(0x000000, 0.65f));
        int ccx = cx + cw / 2, ccy = top + (bottom - top) / 2;
        disc(g, ccx, ccy, 22, alpha(cAccent, 0.18f));
        disc(g, ccx, ccy, 6, cAccent);
        for (int i = 0; i < TABS.length; i++) {
            int[] p = wheelPos(i);
            float hv = ease("wheel" + i, hov("tab" + i) ? 1 : 0, 16);
            float sel = tab == i ? 1f : 0f;
            int r = 16 + Math.round(3 * hv);
            disc(g, p[0], p[1], r + 3, alpha(cAccent, 0.2f + 0.3f * hv));
            disc(g, p[0], p[1], r, mix(mix(cCard, TEXT, 0.08f), cAccent, 0.2f + 0.7f * Math.max(sel, hv)));
            icon(g, i, p[0] - 7, p[1] - 7, mix(TEXT, cBg, Math.max(sel, hv)), mix(cCard, cAccent, Math.max(sel, hv)));
            text(g, TABS[i], p[0] - font.width(TABS[i]) / 2, p[1] + r + 6, TEXT);
        }
        String hint = "Tab to close";
        text(g, hint, ccx - font.width(hint) / 2, bottom - 12, muted());
    }

    private void tooltip(GuiGraphics g, String s, int x, int y, boolean centered) {
        int w = font.width(s) + 10;
        if (centered) x -= w / 2;
        round(g, x, y, w, 16, cLine);
        round(g, x + 1, y + 1, w - 2, 14, cCard);
        text(g, s, x + 5, y + 4, TEXT);
    }

    private void drawTooltips(GuiGraphics g) {
        for (int i = 0; i < TABS.length; i++) {
            if (!hov("tab" + i)) continue;
            String s = TABS[i] + " [" + (i + 1) + "]";
            if (layout == L_RAIL) {
                int[] r = navRect(i);
                tooltip(g, s, r[0] + r[2] + 6, r[1] + (r[3] - 16) / 2, false);
            } else if (layout == L_DOCK) {
                tooltip(g, s, dockX(i) + 15, dockY() - 26, true);
            }
        }
    }

    private void drawHeader(GuiGraphics g, int mx, int my, float partial) {
        // title slides in from the side the new tab came from
        float e = eo(tabT);
        int tx = cx + (layout == L_RADIAL ? 26 : 0) + (int) ((1f - e) * 14f * tabDir);
        g.drawString(font, Component.literal(TABS[tab]).withStyle(ChatFormatting.BOLD), tx, py + 12, alpha(TEXT, Math.max(0.08f, e)), false);

        // search pill
        if (searchActive && search != null) {
            boolean focus = search.isFocused();
            round(g, searchX - 1, py + 7, searchW + 2, 22, focus ? mix(cCard, cAccent, 0.6f) : cLine);
            round(g, searchX, py + 8, searchW, 20, mix(cCard, cBg, 0.2f));
            disc(g, searchX + 10, py + 17, 4, muted());
            disc(g, searchX + 10, py + 17, 2, mix(cCard, cBg, 0.2f));
            g.fill(searchX + 13, py + 20, searchX + 15, py + 22, muted());
            search.render(g, mx, my, partial);
        }

        // status pill
        round(g, stX, py + 8, stW, 20, mix(cCard, cBg, 0.2f));
        disc(g, stX + 10, py + 18, 3, pillColor());
        text(g, pillLabel(), stX + 18, py + 14, mix(TEXT, cBg, 0.15f));

        // skin + name
        String name = Minecraft.getInstance().getUser().getName();
        round(g, nameX, py + 8, nameW, 20, mix(cCard, cBg, 0.2f));
        try { PlayerFaceRenderer.draw(g, skin(), nameX + 3, py + 10, 16); } catch (Exception ignored) { }
        text(g, name, nameX + 24, py + 14, TEXT);

        boolean hc = hov("close");
        round(g, closeX, py + 10, 16, 16, hc ? RED : cCard);
        text(g, "x", closeX + 5, py + 14, TEXT);
    }

    private void drawBottom(GuiGraphics g) {
        String msg = status.getString();
        if (msg.isEmpty()) msg = "1-5 tabs  |  L layout  |  Ctrl+K search";
        text(g, fit(msg, cw - applyW - 16), cx + 2, applyY + 8, muted());
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
        float e = eo(tabT); // numbers and the bar count up whenever the tab opens

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
            bold(g, String.valueOf(Math.round(v * e)), x + 8, top + 22, cols[i]);
            if (i == 0) {
                int bw = w3 - 16;
                g.fill(x + 8, top + 38, x + 8 + bw, top + 42, mix(cCard, cBg, 0.6f));
                float f = ease("bar", on / (float) Math.max(1, total), 6);
                g.fill(x + 8, top + 38, x + 8 + (int) (bw * f * e), top + 42, cAccent);
            }
            x += w3 + gap;
        }

        int y2 = top + h + gap, h2 = bottom - y2;
        card(g, cx, y2, cw, h2);
        bold(g, off == 0 ? "Everything is enabled" : "Turned off", cx + 10, y2 + 8, TEXT);
        int chipX = cx + 10, chipY = y2 + 26, n = 0;
        for (Group gr : Group.ALL) {
            if (!Config.disabled.contains(gr.id())) continue;
            int w = font.width(gr.label()) + 12;
            if (chipX + w > cx + cw - 10) { chipX = cx + 10; chipY += 20; }
            if (chipY + 16 > y2 + h2) break;
            int dx = (int) ((1f - stag(n++)) * 16 * tabDir); // chips pop in one after another
            round(g, chipX + dx, chipY, w, 16, alpha(RED, 0.25f));
            text(g, gr.label(), chipX + 6 + dx, chipY + 4, TEXT);
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
        if (rowGroups.isEmpty()) {
            text(g, searchText.isEmpty() ? "Nothing in this category" : "No groups match \"" + fit(searchText, 160) + "\"", cx + 14, top + 32, muted());
        }
        for (int i = 0; i < rowGroups.size(); i++) {
            Group gr = rowGroups.get(i);
            boolean enabled = !Config.disabled.contains(gr.id());
            int ry = top + 26 + i * rowH;
            int dx = (int) ((1f - stag(i)) * 24 * tabDir); // rows cascade in
            float hv = ease("rowhov" + gr.id(), hov("row" + i) ? 1 : 0, 14);
            if (hv > 0.02f) round(g, cx + 6 + dx, ry, cw - 12, rowH - 2, alpha(cAccent, 0.12f * hv));
            float sw = ease("sw" + gr.id(), enabled ? 1 : 0, 16);
            text(g, fit(gr.label(), cw - 90), cx + 14 + (int) (2 * hv) + dx, ry + 6, mix(muted(), TEXT, sw));
            toggleSwitch(g, cx + cw - 14 - 26 + dx, ry + 4, sw);
        }
    }

    /** Armor trims: one switch for the trim textures, plus an animated preview. */
    private void drawTrims(GuiGraphics g) {
        int lw = trimLeftW(), x0 = cx + lw + 8, w = cw - lw - 8, h = bottom - top;
        boolean enabled = !Config.disabled.contains("trims");
        float sw = ease("sw" + "trims", enabled ? 1 : 0, 16);

        // trim colour glides through the vanilla trim materials
        float mt = time * 0.5f;
        int mi = (int) Math.floor(mt) % MATS.length, mj = (mi + 1) % MATS.length;
        float mf = mt - (float) Math.floor(mt);
        mf = mf * mf * (3f - 2f * mf);
        int trimC = mix(MAT_COL[mi], MAT_COL[mj], mf);

        // ---- left: preview card ----
        card(g, cx, top, lw, h);
        int s = Math.max(2, Math.min(7, Math.min((lw - 20) / 12, (h - 48) / 9)));
        int hx = cx + (lw - 12 * s) / 2, hy = top + 12 + (int) Math.round(2 * Math.sin(time * 2f)); // gentle bob
        float band = (time * 7f) % 24f; // diagonal shine band sweeping across the trim
        for (int r = 0; r < HELM.length; r++) {
            for (int c = 0; c < 12; c++) {
                char ch = HELM[r].charAt(c);
                if (ch == '.') continue;
                int col;
                if (ch == 'T') {
                    float shine = Math.max(0f, 1f - Math.abs((c + r) - band) / 2.5f);
                    col = mix(mix(0xFF4A4A4A, trimC, sw), 0xFFFFFFFF, shine * 0.7f * sw);
                } else {
                    col = mix(0xFF9A9AA6, 0xFF55555F, r / 9f);
                }
                g.fill(hx + c * s, hy + r * s, hx + (c + 1) * s, hy + (r + 1) * s, col);
            }
        }
        String mn = enabled ? MATS[mi] : "Trims off";
        text(g, mn, cx + (lw - font.width(mn)) / 2, hy + 9 * s + 8, mix(TEXT, trimC, 0.6f * sw));

        // ---- right top: the switch ----
        float hv = ease("trimhov", hov("trimsw") ? 1 : 0, 14);
        card(g, x0, top, w, 30);
        if (hv > 0.02f) round(g, x0, top, w, 30, alpha(cAccent, 0.10f * hv));
        bold(g, "Armor trims", x0 + 10, top + 6, TEXT);
        text(g, enabled ? "Kept in pack" : "Removed from pack", x0 + 10, top + 17, enabled ? GREEN : RED);
        toggleSwitch(g, x0 + w - 14 - 26, top + 9, sw);

        // ---- right middle: pattern chips with a travelling highlight wave ----
        boolean mats = h >= 120;
        int cy2 = top + 38, ch2 = h - 38 - (mats ? 28 : 0);
        card(g, x0, cy2, w, ch2);
        text(g, "Patterns", x0 + 8, cy2 + 6, muted());
        int n = PATTERNS.length, chipX = x0 + 8, chipY = cy2 + 20;
        float wave = (time * 6f) % (n + 6);
        int onText = lum(trimC) > 140 ? 0xFF111111 : 0xFFFFFFFF;
        for (int i = 0; i < n; i++) {
            int cwid = font.width(PATTERNS[i]) + 10;
            if (chipX + cwid > x0 + w - 8) { chipX = x0 + 8; chipY += 18; }
            if (chipY + 14 > cy2 + ch2 - 4) break;
            float lit = Math.max(0f, 1f - Math.abs(i - wave) / 3f) * sw;
            int dx = (int) ((1f - stag(i)) * 16 * tabDir);
            round(g, chipX + dx, chipY, cwid, 14, mix(mix(cCard, TEXT, 0.08f), trimC, 0.15f + 0.75f * lit));
            text(g, PATTERNS[i], chipX + 5 + dx, chipY + 3, mix(TEXT, onText, lit));
            chipX += cwid + 4;
        }

        // ---- right bottom: material swatches that hop in sequence ----
        if (mats) {
            int my = top + h - 24, n2 = MATS.length;
            card(g, x0, my, w, 24);
            int sq = Math.max(6, Math.min(12, (w - 16 - (n2 - 1) * 2) / n2));
            int gap2 = Math.max(1, (w - 16 - n2 * sq) / (n2 - 1));
            float hop = (time * 2.2f) % (n2 + 3);
            for (int i = 0; i < n2; i++) {
                float b = Math.max(0f, 1f - Math.abs(i - hop) / 2f) * sw;
                int bx = x0 + 8 + i * (sq + gap2), by = my + 7 - (int) (4 * b);
                int col = mix(0xFF4A4A4A, MAT_COL[i], 0.35f + 0.65f * sw);
                g.fill(bx, by, bx + sq, by + sq, col);
                g.fill(bx, by, bx + sq, by + 1, mix(col, 0xFFFFFFFF, 0.4f));
            }
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
            int dx = (int) ((1f - stag(i)) * 24 * tabDir); // rows cascade in
            float hv = ease("srchov" + i, hov("src" + i) ? 1 : 0, 14);
            if (hv > 0.02f) round(g, cx + 6 + dx, ry, cw - 12, rowH - 2, alpha(cAccent, 0.12f * hv));
            float sw = ease("srcsel" + s, sel ? 1 : 0, 16);
            round(g, cx + 12 + dx, ry + 4, 12, 12, mix(mix(cCard, TEXT, 0.15f), cAccent, sw));
            if (sw > 0.5f) { g.fill(cx + 15 + dx, ry + 9, cx + 17 + dx, ry + 11, cBg); g.fill(cx + 17 + dx, ry + 11, cx + 21 + dx, ry + 12, cBg); g.fill(cx + 19 + dx, ry + 8, cx + 21 + dx, ry + 11, cBg); }
            text(g, fit(s, cw - 120), cx + 32 + dx, ry + 6, TEXT);
            if (enabled.contains(s)) {
                round(g, cx + cw - 46 + dx, ry + 4, 30, 12, alpha(GREEN, 0.3f));
                text(g, "ON", cx + cw - 38 + dx, ry + 6, GREEN);
            }
        }
    }

    // ---- settings ----
    private void drawSettings(GuiGraphics g) {
        int chipX = cx + 8;
        for (int i = 0; i < SET_CHIPS.length; i++) {
            int w = font.width(SET_CHIPS[i]) + 14;
            float sel = ease("schip" + i, setChip == i ? 1 : 0, 14);
            round(g, chipX, top, w, 16, mix(cCard, cAccent, 0.15f + 0.8f * sel));
            text(g, SET_CHIPS[i], chipX + 7, top + 4, mix(TEXT, cBg, sel));
            chipX += w + 4;
        }
        int y0 = top + 22;
        if (setChip == 0) drawLayoutPage(g, y0);
        else if (setChip == 1) drawThemePage(g, y0);
        else drawDisplayPage(g, y0);
    }

    private void layoutPreview(GuiGraphics g, int i, int x, int y, int w, int h, int accent) {
        int body = mix(cCard, cBg, 0.55f), bar = mix(cCard, TEXT, 0.22f);
        round(g, x, y, w, h, body);
        switch (i) {
            case L_CLASSIC -> {
                g.fill(x + 2, y + 2, x + 2 + w / 3, y + h - 2, bar);
                for (int k = 0; k < 3; k++) g.fill(x + 4, y + 5 + k * 5, x + w / 3, y + 7 + k * 5, k == 0 ? accent : mix(bar, TEXT, 0.25f));
            }
            case L_RAIL -> {
                g.fill(x + 2, y + 2, x + 8, y + h - 2, bar);
                for (int k = 0; k < 3; k++) g.fill(x + 3, y + 5 + k * 5, x + 7, y + 7 + k * 5, k == 0 ? accent : mix(bar, TEXT, 0.25f));
            }
            case L_TABS -> {
                for (int k = 0; k < 3; k++) round(g, x + 3 + k * 12, y + 3, 10, 4, k == 0 ? accent : bar);
            }
            case L_DOCK -> {
                for (int k = 0; k < 4; k++) round(g, x + w / 2 - 14 + k * 8, y + h - 7, 6, 4, k == 1 ? accent : bar);
            }
            default -> {
                int ccx = x + w / 2, ccy = y + h / 2, r = Math.max(4, Math.min(w, h) / 3);
                for (int k = 0; k < 5; k++) {
                    double a = -Math.PI / 2 + k * 2 * Math.PI / 5;
                    disc(g, ccx + (int) Math.round(r * Math.cos(a)), ccy + (int) Math.round(r * Math.sin(a)), 2, k == 0 ? accent : bar);
                }
            }
        }
    }

    private void drawLayoutPage(GuiGraphics g, int y0) {
        text(g, "Where the navigation lives. Press L anywhere to cycle.", cx + 4, y0 - 2 + 0, muted());
        int gw = layCardW(), gh = layCardH(), yy = y0 + 10;
        for (int i = 0; i < LAYOUTS.length; i++) {
            int x = cx + 6 + i * (gw + 6), y = yy + (int) ((1f - stag(i)) * 12f);
            boolean sel = layout == i;
            float hv = ease("layhov" + i, hov("lay" + i) ? 1 : 0, 14), sv = ease("laysel" + i, sel ? 1 : 0, 12);
            round(g, x - 1, y - 1, gw + 2, gh - 8, mix(cLine, cAccent, 0.25f * hv + 0.85f * sv));
            round(g, x, y, gw, gh - 10, cCard);
            layoutPreview(g, i, x + 5, y + 5, gw - 10, Math.max(14, gh - 10 - 40), cAccent);
            bold(g, fit(LAYOUTS[i], gw - 24), x + 6, y + gh - 10 - 32, mix(TEXT, cAccent, sv * 0.5f));
            text(g, fit(LAYOUT_SUB[i], gw - 10), x + 6, y + gh - 10 - 20, muted());
            if (sv > 0.5f) { disc(g, x + gw - 10, y + gh - 10 - 28, 5, cAccent); g.fill(x + gw - 13, y + gh - 10 - 28, x + gw - 11, y + gh - 10 - 26, cBg); g.fill(x + gw - 11, y + gh - 10 - 26, x + gw - 7, y + gh - 10 - 25, cBg); }
        }
    }

    private void drawThemePage(GuiGraphics g, int y0) {
        int cellH = themeCellH(), gw = (cw - 5 * 6) / 4;
        for (int i = 0; i < Theme.ALL.size(); i++) {
            Theme t = Theme.ALL.get(i);
            int x = cx + 6 + (i % 4) * (gw + 6), y = y0 + (i / 4) * (cellH + 4) + (int) ((1f - stag(i / 4 + i % 4)) * 12f); // cells rise in diagonally
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
    }

    private void drawDisplayPage(GuiGraphics g, int y0) {
        int dw = dispCardW(), ch = 84;
        int rx = cx + dw + 8;
        int la = (int) ((1f - stag(0)) * 14f), lb = (int) ((1f - stag(1)) * 14f);

        // left card: display and effects
        card(g, cx, y0 + la, dw, ch);
        bold(g, "Display and effects", cx + 8, y0 + 7 + la, TEXT);
        optionRow(g, 0, cx + 6, y0 + 22 + la, dw - 12, "Animations", null, Config.anim);
        optionRow(g, 1, cx + 6, y0 + 40 + la, dw - 12, "Particle amount", INTENSITY[Config.intensity], true);
        optionRow(g, 2, cx + 6, y0 + 58 + la, dw - 12, "Glow pulse", null, Config.glow);

        // right card: bulk pack switches
        int total = Group.ALL.size(), off = 0;
        for (Group gr : Group.ALL) if (Config.disabled.contains(gr.id())) off++;
        card(g, rx, y0 + lb, dw, ch);
        bold(g, "Pack toggles", rx + 8, y0 + 7 + lb, TEXT);
        text(g, (total - off) + " of " + total + " groups on", rx + 8, y0 + 24 + lb, muted());
        text(g, "Sources: " + Config.sources.size() + " pack(s)", rx + 8, y0 + 35 + lb, muted());
        int bw = (dw - 24) / 2;
        round(g, rx + 8, y0 + 48 + lb, bw, 18, hov("allon2") ? mix(cCard, GREEN, 0.5f) : mix(cCard, TEXT, 0.08f));
        text(g, "Enable all", rx + 8 + (bw - font.width("Enable all")) / 2, y0 + 53 + lb, TEXT);
        round(g, rx + 16 + bw, y0 + 48 + lb, bw, 18, hov("alloff2") ? mix(cCard, RED, 0.5f) : mix(cCard, TEXT, 0.08f));
        text(g, "Disable all", rx + 16 + bw + (bw - font.width("Disable all")) / 2, y0 + 53 + lb, TEXT);
    }

    private void optionRow(GuiGraphics g, int i, int x, int y, int w, String label, String value, boolean on) {
        float hv = ease("opthov" + i, hov("opt" + i) ? 1 : 0, 14);
        round(g, x, y, w, 16, alpha(cAccent, 0.06f + 0.12f * hv));
        text(g, label, x + 8, y + 4, TEXT);
        if (value != null) {
            text(g, value, x + w - 8 - font.width(value), y + 4, cAccent2);
        } else {
            float sw = ease("optsw" + i, on ? 1 : 0, 16);
            toggleSwitch(g, x + w - 8 - 26, y + 2, sw);
        }
    }

    @Override
    public void onClose() {
        Config.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
