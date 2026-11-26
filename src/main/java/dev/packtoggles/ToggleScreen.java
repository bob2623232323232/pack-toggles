package dev.packtoggles;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Trim Studio: 3D armor-trim preview, saved loadouts (Wardrobe), a pixel skin editor, a Discord bot and settings.
 * Everything is drawn by hand in render(); vanilla Buttons are transparent click areas. Mouse dragging
 * (painting, colour bars, preview rotation) is polled each frame. The skin canvas and previews are drawn
 * from small dynamic textures, so the skin tab costs a handful of draw calls instead of thousands.
 */
public class ToggleScreen extends Screen {
    private static final int TEXT = 0xFFF5E8DD, GREEN = 0xFF58D26F, RED = 0xFFE0564F, AMBER = 0xFFF0B429;
    private static final String[] TABS = {"Trims", "Wardrobe", "Skin", "Discord", "Settings"};
    private static final int T_TRIMS = 0, T_WARDROBE = 1, T_SKIN = 2, T_DISCORD = 3, T_SETTINGS = 4;
    private static final String[] INTENSITY = {"Low", "Medium", "High"};
    private static final String[] SET_CHIPS = {"Layout", "Theme", "Display", "Colors"};
    private static final String[] SPEED = {"Slow", "Normal", "Fast"};
    private static final float[] SPEED_MUL = {0.6f, 1f, 1.5f};
    private static final String[] STYLE = {"Solid", "Soft", "Glass"};
    private static final float[] STYLE_A = {1f, 0.88f, 0.7f};
    private static final String[] GLOWS = {"Soft", "Strong"};

    // layouts
    private static final int L_CLASSIC = 0, L_RAIL = 1, L_TABS = 2, L_DOCK = 3, L_RADIAL = 4;
    private static final String[] LAYOUTS = {"Classic", "Rail", "Tabs", "Dock", "Radial"};
    private static final String[] LAYOUT_SUB = {"Sidebar with labels", "Slim icon rail", "Pill tabs across the top",
            "Magnifying dock at the bottom", "Wheel menu, Tab to open"};

    // trims data
    private static final String[] PATTERNS = {"Sentry", "Dune", "Coast", "Wild", "Ward", "Eye", "Vex", "Tide", "Snout",
            "Rib", "Spire", "Wayfinder", "Shaper", "Silence", "Raiser", "Host", "Flow", "Bolt"};
    private static final String[] MATS = {"Quartz", "Iron", "Netherite", "Redstone", "Copper", "Gold",
            "Emerald", "Diamond", "Lapis", "Amethyst", "Resin"};
    private static final int[] MAT_COL = {0xFFE3D4C4, 0xFFCECACA, 0xFF625859, 0xFF971607, 0xFFB4684D, 0xFFDEB12D,
            0xFF11A036, 0xFF6EECD2, 0xFF416E97, 0xFF9A5CC6, 0xFFFC7812};
    private static final String[] ARMOR_NAMES = {"Leather", "Chainmail", "Iron", "Gold", "Diamond", "Netherite"};
    private static final String[] ARMOR_IDS = {"leather", "chainmail", "iron", "golden", "diamond", "netherite"};
    private static final String[] PIECE_NAMES = {"Helmet", "Chest", "Legs", "Boots", "Showcase", "Random"};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    // skin editor data: boxes are {u, v, w, h, d} of the standard player skin unwrap
    private static final int[][] BOXES = {
            {0, 0, 8, 8, 8}, {16, 16, 8, 12, 4}, {40, 16, 4, 12, 4}, {0, 16, 4, 12, 4}, {32, 48, 4, 12, 4}, {16, 48, 4, 12, 4},
            {32, 0, 8, 8, 8}, {16, 32, 8, 12, 4}, {40, 32, 4, 12, 4}, {48, 48, 4, 12, 4}, {0, 32, 4, 12, 4}, {0, 48, 4, 12, 4}};
    // preview parts: {dstX, dstY, w, h, baseU, baseV, overlayU, overlayV}
    private static final int[][] FRONT = {
            {4, 0, 8, 8, 8, 8, 40, 8}, {4, 8, 8, 12, 20, 20, 20, 36}, {0, 8, 4, 12, 44, 20, 44, 36},
            {12, 8, 4, 12, 36, 52, 52, 52}, {4, 20, 4, 12, 4, 20, 4, 36}, {8, 20, 4, 12, 20, 52, 4, 52}};
    private static final int[][] BACK = {
            {4, 0, 8, 8, 24, 8, 56, 8}, {4, 8, 8, 12, 32, 20, 32, 36}, {0, 8, 4, 12, 44, 52, 60, 52},
            {12, 8, 4, 12, 52, 20, 52, 36}, {4, 20, 4, 12, 28, 52, 12, 52}, {8, 20, 4, 12, 12, 20, 12, 36}};
    private static final int[] PALETTE = {0xFF1A1A1A, 0xFF555555, 0xFFAAAAAA, 0xFFFFFFFF, 0xFFC68863, 0xFF8B5A2B, 0xFF5B3A1E, 0xFFE8C39E,
            0xFFD9382C, 0xFFF2762B, 0xFFF0B429, 0xFF58D26F, 0xFF1D9E75, 0xFF378ADD, 0xFF534AB7, 0xFFD4537E};
    private static final String[] TOOLS = {"Pencil", "Eraser", "Fill", "Pick"};
    private static final String[] BRUSHES = {"1px", "2px", "3px"};
    private static final boolean[] USED = new boolean[64 * 64];

    static {
        for (int[] b : BOXES) {
            int u = b[0], v = b[1], w = b[2], h = b[3], d = b[4];
            mark(u + d, v, w, d); mark(u + d + w, v, w, d);
            mark(u, v + d, d, h); mark(u + d, v + d, w, h); mark(u + d + w, v + d, d, h); mark(u + 2 * d + w, v + d, w, h);
        }
    }

    private static void mark(int x, int y, int w, int h) {
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) USED[(y + j) * 64 + x + i] = true;
    }

    // session + saved state
    private static int layout = L_RAIL;
    private static int armorType = 5, material = 0, pattern = 0;
    private static final boolean[] pieceOn = {true, true, true, true};
    private static boolean showcase = false, sway = true, followCursor = false, glowStrong = false;
    private static int speedIdx = 1, styleIdx = 0;
    private static boolean customOn = false;
    private static int customAccent = 0xFFF2762B, customAccent2 = 0xFFFFB347;
    private static float viewYaw = 0.35f;
    private static int[] canvas = null;
    private static boolean skinDirty = false, skinChanged = true, equipDirty = true;
    private static final List<int[]> undo = new ArrayList<>();
    private static int curColor = 0xFFC68863, tool = 0, fileIdx = -1, brush = 1;
    private static final List<String> wardrobe = new ArrayList<>();
    private static final boolean ESSENTIAL = FabricLoader.getInstance().isModLoaded("essential");
    private static boolean prefsLoaded = false;

    private final Screen parent;
    private final FX fx = new FX();
    private final Map<String, Float> eased = new HashMap<>();
    private final Map<String, Button> hits = new HashMap<>();
    private int tab = 0, setChip = 0, wPage = 0;
    private float openT = 0, tabT = 1;
    private int tabDir = 1;
    private float selX = Float.NaN, selY = 0, selVX = 0, selVY = 0;
    private long lastNanos = System.nanoTime();
    private float dt = 0.016f, time = 0, partialTick = 0;
    private Component status = Component.empty();
    private Theme theme = Theme.byId(Config.theme);
    private int cAccent, cAccent2, cBg, cCard, cLine;
    private boolean colorsInit = false;

    // text fields
    private EditBox search, tokenBox, chanBox, userBox;
    private final List<EditBox> fields = new ArrayList<>();
    private String searchText = "";
    private boolean searchActive = false, building = false, dirty = false, refocus = false;
    private boolean radialOpen = false;

    // layout
    private int px, py, pw, ph, cx, cw, top, bottom, baseCx;
    private int tabStep = 34, tabH = 28;
    private int applyX, applyY, applyW = 150, applyH = 24;
    private int closeX, nameX, nameW, stX, stW, searchX, searchW;

    // trims tab
    private RemotePlayer dummy;
    private boolean worldMissing = false;
    private float showTimer = 0;
    private int[] prevBox = new int[4];
    private boolean prevDrag = false;
    private int lastMx = 0;
    private final List<int[]> rArmor = new ArrayList<>(), rPiece = new ArrayList<>(), rMat = new ArrayList<>(), rPat = new ArrayList<>();
    private final List<Integer> patIdx = new ArrayList<>();
    private int yLA, yLB, yLC, yLD;

    // skin tab
    private int cs = 3, cvX, cvY, rX, rW, ctlW, pvScale = 3, palSw = 12;
    private boolean wasPressed = false, canvasDrag = false;
    private int sliderDrag = -1;
    private DynamicTexture canvasTex;
    private NativeImage canvasImg;
    private Identifier canvasId;
    private int canvasSize = 0;
    private final DynamicTexture[] pvTex = new DynamicTexture[2];
    private final NativeImage[] pvImg = new NativeImage[2];
    private final Identifier[] pvId = new Identifier[2];
    private int pvSize = 0;
    private boolean texDirty = true;

    // wardrobe tab
    private int wRows = 1;

    public ToggleScreen(Screen parent) {
        super(Component.translatable("screen.packtoggles.title"));
        this.parent = parent;
        loadPrefs();
        PackTogglesClient.LOG.info("[Trim Studio] screen opened: build 3.0.0, layout={}, essential={}", LAYOUTS[layout], ESSENTIAL);
        if (canvas == null) { canvas = new int[64 * 64]; template(canvas); }
    }

    // ---------------- saved settings ----------------
    private static Path prefsPath() { return FabricLoader.getInstance().getConfigDir().resolve("trim-studio.properties"); }

    private static int geti(Properties p, String k, int d) {
        try { return Integer.parseInt(p.getProperty(k, "" + d).trim()); } catch (Exception e) { return d; }
    }

    private static boolean getb(Properties p, String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, "" + d).trim()); }

    private static int clampi(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static void loadPrefs() {
        if (prefsLoaded) return;
        prefsLoaded = true;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(prefsPath())) { p.load(in); } catch (Exception e) { return; }
        layout = clampi(geti(p, "layout", layout), 0, 4);
        armorType = clampi(geti(p, "armor", armorType), 0, ARMOR_NAMES.length - 1);
        material = clampi(geti(p, "material", material), 0, MATS.length - 1);
        pattern = clampi(geti(p, "pattern", pattern), 0, PATTERNS.length - 1);
        String pcs = p.getProperty("pieces", "1111");
        for (int i = 0; i < 4; i++) pieceOn[i] = pcs.length() <= i || pcs.charAt(i) == '1';
        sway = getb(p, "sway", sway);
        followCursor = getb(p, "follow", followCursor);
        glowStrong = getb(p, "glowStrong", glowStrong);
        speedIdx = clampi(geti(p, "speed", speedIdx), 0, 2);
        styleIdx = clampi(geti(p, "style", styleIdx), 0, 2);
        customOn = getb(p, "customOn", customOn);
        customAccent = 0xFF000000 | geti(p, "accent", customAccent);
        customAccent2 = 0xFF000000 | geti(p, "accent2", customAccent2);
        brush = clampi(geti(p, "brush", brush), 1, 3);
        DiscordBot.token = p.getProperty("dc.token", "");
        DiscordBot.channelId = p.getProperty("dc.channel", "");
        DiscordBot.userId = p.getProperty("dc.user", "");
        DiscordBot.listen = getb(p, "dc.listen", true);
        DiscordBot.autoPost = getb(p, "dc.autopost", false);
        wardrobe.clear();
        for (String s : p.getProperty("wardrobe", "").split(";")) if (!s.isBlank() && entry(s) != null) wardrobe.add(s);
    }

    static void savePrefs() {
        Properties p = new Properties();
        p.setProperty("layout", "" + layout);
        p.setProperty("armor", "" + armorType);
        p.setProperty("material", "" + material);
        p.setProperty("pattern", "" + pattern);
        StringBuilder pcs = new StringBuilder();
        for (boolean b : pieceOn) pcs.append(b ? '1' : '0');
        p.setProperty("pieces", pcs.toString());
        p.setProperty("sway", "" + sway);
        p.setProperty("follow", "" + followCursor);
        p.setProperty("glowStrong", "" + glowStrong);
        p.setProperty("speed", "" + speedIdx);
        p.setProperty("style", "" + styleIdx);
        p.setProperty("customOn", "" + customOn);
        p.setProperty("accent", "" + (customAccent & 0xFFFFFF));
        p.setProperty("accent2", "" + (customAccent2 & 0xFFFFFF));
        p.setProperty("brush", "" + brush);
        p.setProperty("dc.token", DiscordBot.token);
        p.setProperty("dc.channel", DiscordBot.channelId);
        p.setProperty("dc.user", DiscordBot.userId);
        p.setProperty("dc.listen", "" + DiscordBot.listen);
        p.setProperty("dc.autopost", "" + DiscordBot.autoPost);
        p.setProperty("wardrobe", String.join(";", wardrobe));
        try (OutputStream out = Files.newOutputStream(prefsPath())) { p.store(out, "Trim Studio settings (contains your Discord bot token, keep private)"); }
        catch (Exception e) { PackTogglesClient.LOG.warn("Could not save Trim Studio settings", e); }
    }

    // ---------------- shared logic (also used by the Discord bot) ----------------
    static String giveCommand() {
        return "/give @s " + ARMOR_IDS[armorType] + "_chestplate[trim={pattern:\"" + PATTERNS[pattern].toLowerCase(Locale.ROOT)
                + "\",material:\"" + MATS[material].toLowerCase(Locale.ROOT) + "\"}]";
    }

    static String loadoutText() {
        return "**" + ARMOR_NAMES[armorType] + "** armor with the **" + PATTERNS[pattern] + "** trim in **" + MATS[material] + "**\n`" + giveCommand() + "`";
    }

    static String randomTrim() {
        Random r = new Random();
        armorType = r.nextInt(ARMOR_NAMES.length);
        material = r.nextInt(MATS.length);
        pattern = r.nextInt(PATTERNS.length);
        equipDirty = true;
        return loadoutText();
    }

    static String setLoadout(String args) {
        int a = -1, m = -1, p = -1;
        for (String w : args.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            for (int i = 0; i < ARMOR_NAMES.length; i++) if (w.equals(ARMOR_NAMES[i].toLowerCase(Locale.ROOT)) || w.equals(ARMOR_IDS[i])) a = i;
            for (int i = 0; i < PATTERNS.length; i++) if (w.equals(PATTERNS[i].toLowerCase(Locale.ROOT))) p = i;
            for (int i = 0; i < MATS.length; i++) if (w.equals(MATS[i].toLowerCase(Locale.ROOT))) m = i;
        }
        if (a < 0 && m < 0 && p < 0) return "Usage: `!set <armor> <pattern> <material>`, for example `!set diamond wild gold`";
        if (a >= 0) armorType = a;
        if (m >= 0) material = m;
        if (p >= 0) pattern = p;
        equipDirty = true;
        return loadoutText();
    }

    static byte[] skinPng() {
        try {
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < canvas.length; i++) img.setRGB(i % 64, i / 64, canvas[i]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static int[] entry(String s) {
        try {
            String[] t = s.split(",");
            int[] e = {Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])};
            if (e[0] < 0 || e[0] >= ARMOR_NAMES.length || e[1] < 0 || e[1] >= MATS.length || e[2] < 0 || e[2] >= PATTERNS.length) return null;
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------------- helpers ----------------
    private float ease(String key, float target, float speed) {
        float v = eased.getOrDefault(key, target);
        v += (target - v) * Math.min(1f, dt * speed);
        eased.put(key, v);
        return v;
    }

    private static float eo(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private float stag(int i) { return eo((tabT - Math.min(i, 12) * 0.04f) / 0.55f); }

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

    /** Standard "source over destination" blend for ARGB ints. */
    private static int over(int d, int s) {
        int sa = s >>> 24;
        if (sa == 0) return d;
        if (sa == 255) return s;
        int da = d >>> 24;
        if (da == 0) return s;
        float a = sa / 255f, ba = da / 255f, oa = a + ba * (1 - a);
        int r = Math.round(((s >> 16 & 255) * a + (d >> 16 & 255) * ba * (1 - a)) / oa);
        int g = Math.round(((s >> 8 & 255) * a + (d >> 8 & 255) * ba * (1 - a)) / oa);
        int b = Math.round(((s & 255) * a + (d & 255) * ba * (1 - a)) / oa);
        return (Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | b;
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

    private void toggleSwitch(GuiGraphics g, int x, int y, float sw) {
        round(g, x, y, 26, 12, mix(mix(cCard, TEXT, 0.15f), cAccent, sw));
        round(g, x + 1 + (int) (14 * sw), y + 1, 10, 10, 0xFFFFFFFF);
    }

    private boolean hov(String key) { Button b = hits.get(key); return b != null && b.isHoveredOrFocused(); }

    private void hit(String key, int x, int y, int w, int h, Runnable r) {
        Button b = Button.builder(Component.empty(), btn -> r.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f);
        addRenderableWidget(b);
        hits.put(key, b);
    }

    private void chip(GuiGraphics g, String key, int[] r, String label, float sel, int selCol) {
        float hv = ease("h" + key, hov(key) ? 1 : 0, 14);
        float sv = ease("s" + key, sel, 14);
        round(g, r[0], r[1], r[2], r[3], mix(mix(cCard, TEXT, 0.06f + 0.06f * hv), selCol, 0.9f * sv));
        int fg = sv > 0.5f ? (lum(selCol) > 140 ? 0xFF111111 : 0xFFFFFFFF) : TEXT;
        text(g, label, r[0] + (r[2] - font.width(label)) / 2, r[1] + (r[3] - 8) / 2, fg);
    }

    private void setTab(int t) {
        radialOpen = false;
        if (t == tab) { init(); return; }
        restartEntrance(t > tab ? 1 : -1);
        tab = t;
        init();
    }

    private void setLayout(int l) {
        layout = ((l % LAYOUTS.length) + LAYOUTS.length) % LAYOUTS.length;
        radialOpen = false;
        selX = Float.NaN;
        restartEntrance(1);
        init();
    }

    private String pillLabel() { return skinDirty ? "Unsaved skin" : DiscordBot.isRunning() ? "Bot online" : "Ready"; }

    private int pillColor() { return skinDirty ? AMBER : GREEN; }

    private boolean anyTyping() {
        if (search != null && search.isFocused()) return true;
        for (EditBox b : fields) if (b.isFocused()) return true;
        return false;
    }

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
        fields.clear();
        search = null;
        tokenBox = chanBox = userBox = null;
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
        baseCx = cx;
        applyY = py + ph - 8 - applyH - (layout == L_DOCK ? 38 : 0);
        applyX = cx + cw - applyW;
        bottom = applyY - 8;

        tabStep = Math.max(22, Math.min(34, (ph - 44 - 12 - (layout == L_CLASSIC ? 44 : 0)) / TABS.length));
        tabH = Math.min(28, tabStep - 4);

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

        closeX = px + pw - 26;
        String name = Minecraft.getInstance().getUser().getName();
        nameW = font.width(name) + 16 + 22;
        nameX = closeX - 8 - nameW;
        stW = Math.max(font.width("Unsaved skin"), Math.max(font.width("Bot online"), font.width("Ready"))) + 26;
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
                if (tab != T_TRIMS) { tab = T_TRIMS; restartEntrance(1); }
                dirty = true;
                refocus = true;
            });
            addWidget(search);
        }

        hit("close", closeX, py + 10, 16, 16, this::onClose);
        hit("apply", applyX, applyY, applyW, applyH, this::doApply);

        if (layout == L_RADIAL && radialOpen) return;
        switch (tab) {
            case T_TRIMS -> initTrims();
            case T_WARDROBE -> initWardrobe();
            case T_SKIN -> initSkin();
            case T_DISCORD -> initDiscord();
            case T_SETTINGS -> initSettings();
            default -> { }
        }
    }

    // ---- trims tab layout ----
    private int trimLeftW() { return Math.max(130, Math.min(210, cw * 2 / 5)); }

    private int[] widths(String[] labels, int pad) {
        int[] w = new int[labels.length];
        for (int i = 0; i < labels.length; i++) w[i] = font.width(labels[i]) + pad;
        return w;
    }

    private int flow(List<int[]> out, int[] ws, int x0, int y, int w, int h, int gap) {
        out.clear();
        int x = x0, yy = y;
        for (int wd : ws) {
            if (x + wd > x0 + w && x > x0) { x = x0; yy += h + gap; }
            out.add(new int[]{x, yy, wd, h});
            x += wd + gap;
        }
        return yy + h;
    }

    private void initTrims() {
        int lw = trimLeftW(), x0 = cx + lw + 8, w = cw - lw - 8;
        prevBox = new int[]{cx + 6, top + 6, cx + lw - 6, bottom - 34};
        int y = top;
        yLA = y; y += 11;
        y = flow(rArmor, widths(ARMOR_NAMES, 10), x0, y, w, 16, 3) + 8;
        yLB = y; y += 11;
        y = flow(rPiece, widths(PIECE_NAMES, 10), x0, y, w, 16, 3) + 8;
        yLC = y; y += 11;
        int[] sq = new int[MATS.length];
        Arrays.fill(sq, 16);
        y = flow(rMat, sq, x0, y, w, 16, 3) + 8;
        yLD = y; y += 11;
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        patIdx.clear();
        for (int i = 0; i < PATTERNS.length; i++) if (q.isEmpty() || PATTERNS[i].toLowerCase(Locale.ROOT).contains(q)) patIdx.add(i);
        int[] pws = new int[patIdx.size()];
        for (int k = 0; k < pws.length; k++) pws[k] = font.width(PATTERNS[patIdx.get(k)]) + 10;
        flow(rPat, pws, x0, y, w, 16, 3);

        for (int i = 0; i < rArmor.size(); i++) {
            final int idx = i; int[] r = rArmor.get(i);
            hit("arm" + i, r[0], r[1], r[2], r[3], () -> { armorType = idx; equipDirty = true; });
        }
        for (int i = 0; i < rPiece.size(); i++) {
            final int idx = i; int[] r = rPiece.get(i);
            hit("pc" + i, r[0], r[1], r[2], r[3], () -> {
                if (idx < 4) { pieceOn[idx] = !pieceOn[idx]; equipDirty = true; }
                else if (idx == 4) showcase = !showcase;
                else { randomTrim(); status = Component.literal("Random: " + ARMOR_NAMES[armorType] + ", " + PATTERNS[pattern] + ", " + MATS[material]); }
            });
        }
        for (int i = 0; i < rMat.size(); i++) {
            final int idx = i; int[] r = rMat.get(i);
            hit("mat" + i, r[0], r[1], r[2], r[3], () -> { material = idx; equipDirty = true; });
        }
        for (int k = 0; k < rPat.size(); k++) {
            final int pi = patIdx.get(k); int[] r = rPat.get(k);
            if (r[1] + r[3] > bottom) continue;
            hit("pat" + k, r[0], r[1], r[2], r[3], () -> { pattern = pi; equipDirty = true; });
        }
    }

    // ---- wardrobe tab layout ----
    private void initWardrobe() {
        wRows = Math.max(1, (bottom - top - 28) / 22);
        int pages = Math.max(1, (wardrobe.size() + wRows - 1) / wRows);
        wPage = Math.min(wPage, pages - 1);
        hit("wsave", cx + 6, top, 104, 16, this::saveLoadout);
        hit("wprev", cx + cw - 44, top, 18, 16, () -> { wPage = (wPage + pages - 1) % pages; restartEntrance(-1); init(); });
        hit("wnext", cx + cw - 24, top, 18, 16, () -> { wPage = (wPage + 1) % pages; restartEntrance(1); init(); });
        for (int i = 0; i < wRows; i++) {
            final int idx = wPage * wRows + i;
            if (idx >= wardrobe.size()) break;
            hit("wrow" + i, cx + 6, top + 22 + i * 22, cw - 12 - 24, 20, () -> loadLoadout(idx));
            hit("wdel" + i, cx + cw - 6 - 22, top + 22 + i * 22, 22, 20, () -> deleteLoadout(idx));
        }
    }

    private void saveLoadout() {
        if (wardrobe.size() >= 60) { status = Component.literal("Wardrobe is full (60 saved)"); return; }
        int mask = 0;
        for (int i = 0; i < 4; i++) if (pieceOn[i]) mask |= 1 << i;
        wardrobe.add(armorType + "," + material + "," + pattern + "," + mask);
        savePrefs();
        status = Component.literal("Saved " + ARMOR_NAMES[armorType] + " " + PATTERNS[pattern] + " " + MATS[material]);
        init();
    }

    private void loadLoadout(int idx) {
        if (idx < 0 || idx >= wardrobe.size()) return;
        int[] e = entry(wardrobe.get(idx));
        if (e == null) return;
        armorType = e[0]; material = e[1]; pattern = e[2];
        for (int i = 0; i < 4; i++) pieceOn[i] = (e[3] >> i & 1) != 0;
        equipDirty = true;
        status = Component.literal("Loaded " + ARMOR_NAMES[armorType] + ", " + PATTERNS[pattern] + ", " + MATS[material] + " (open Trims to see it)");
    }

    private void deleteLoadout(int idx) {
        if (idx < 0 || idx >= wardrobe.size()) return;
        wardrobe.remove(idx);
        savePrefs();
        status = Component.literal("Removed from wardrobe");
        init();
    }

    // ---- skin tab layout ----
    private int yBrush() { return top + 20; }

    private int yBars() { return top + 42; }

    private int yHex() { return top + 88; }

    private int yPal() { return top + 110; }

    private void initSkin() {
        cs = Math.max(2, Math.min(4, (bottom - top - 8) / 64));
        int cvW = 64 * cs;
        cvX = cx + 6;
        cvY = top + 2;
        rX = cvX + cvW + 12;
        rW = cx + cw - rX;
        pvScale = rW >= 250 ? 3 : 2;
        ctlW = rW - (2 * 16 * pvScale + 8) - 8;
        palSw = Math.max(10, (ctlW - 7 * 3) / 8);
        ensureTextures();

        int tw = (ctlW - 3 * 4) / 4;
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            hit("tool" + i, rX + i * (tw + 4), top, tw, 16, () -> tool = idx);
        }
        int bw3 = (ctlW - 2 * 4) / 3;
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            hit("brush" + i, rX + i * (bw3 + 4), yBrush(), bw3, 16, () -> { brush = idx + 1; savePrefs(); });
        }
        for (int i = 0; i < 16; i++) {
            final int c = PALETTE[i];
            hit("pal" + i, rX + (i % 8) * (palSw + 3), yPal() + (i / 8) * (palSw + 3), palSw, palSw, () -> curColor = c);
        }
        int by = yPal() + 2 * (palSw + 3) + 6, bw = (ctlW - 4) / 2;
        hit("undo", rX, by, bw, 18, this::undoSkin);
        hit("tmpl", rX + bw + 4, by, bw, 18, () -> { pushUndo(); template(canvas); skinDirty = true; skinChanged = true; status = Component.literal("Template skin restored"); });
        hit("load", rX, by + 22, bw, 18, this::loadSkin);
        hit("clear", rX + bw + 4, by + 22, bw, 18, () -> { pushUndo(); Arrays.fill(canvas, 0); skinDirty = true; skinChanged = true; status = Component.literal("Canvas cleared"); });
        hit("folder", rX, by + 44, bw, 18, this::openFolder);
        hit("sdisc", rX + bw + 4, by + 44, bw, 18, this::sendSkinToDiscord);
    }

    // ---- discord tab layout ----
    private int discLeftW() { return Math.max(190, Math.min(260, cw * 11 / 20)); }

    private EditBox makeBox(int x, int y, int w, String value, Consumer<String> onChange, boolean mask) {
        EditBox b = new EditBox(font, x + 5, y + 4, w - 10, 12, Component.literal("field"));
        b.setBordered(false);
        b.setMaxLength(120);
        if (mask) b.setFormatter((s, i) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
        building = true;
        b.setValue(value);
        building = false;
        b.setResponder(s -> { if (!building) onChange.accept(s); });
        addWidget(b);
        fields.add(b);
        return b;
    }

    private void initDiscord() {
        int lw = discLeftW(), fx0 = cx + 12, fw = lw - 24;
        tokenBox = makeBox(fx0, top + 43, fw, DiscordBot.token, s -> DiscordBot.token = s.trim(), true);
        chanBox = makeBox(fx0, top + 73, fw, DiscordBot.channelId, s -> DiscordBot.channelId = s.trim(), false);
        userBox = makeBox(fx0, top + 103, fw, DiscordBot.userId, s -> DiscordBot.userId = s.trim(), false);
        hit("opt5", cx + 6, top + 123, lw - 12, 16, () -> DiscordBot.listen = !DiscordBot.listen);
        hit("opt6", cx + 6, top + 141, lw - 12, 16, () -> DiscordBot.autoPost = !DiscordBot.autoPost);
        int bw = (lw - 24 - 8) / 3, by = top + 163;
        hit("dcon", fx0, by, bw, 18, () -> {
            if (DiscordBot.isRunning()) DiscordBot.stop(); else { savePrefs(); DiscordBot.start(); }
        });
        hit("dtrim", fx0 + bw + 4, by, bw, 18, () -> DiscordBot.sendText(loadoutText()));
        hit("dskin", fx0 + 2 * (bw + 4), by, bw, 18, this::sendSkinToDiscord);
    }

    private void sendSkinToDiscord() {
        byte[] png = skinPng();
        if (png.length == 0) { status = Component.literal("Could not build the skin PNG"); return; }
        DiscordBot.sendFile("Skin from Trim Studio", "skin.png", png);
        status = Component.literal("Sending skin to Discord...");
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

    private int[] colBar(int col, int ch) {
        int half = (cw - 16) / 2;
        return new int[]{baseCx + 8 + col * half + 14, top + 22 + 52 + ch * 16, half - 28, 10};
    }

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
        } else if (setChip == 2) {
            int dw = dispCardW();
            hit("opt0", cx + 6, y0 + 22, dw - 12, 16, () -> Config.anim = !Config.anim);
            hit("opt1", cx + 6, y0 + 40, dw - 12, 16, () -> Config.intensity = (Config.intensity + 1) % 3);
            hit("opt2", cx + 6, y0 + 58, dw - 12, 16, () -> Config.glow = !Config.glow);
            hit("opt7", cx + 6, y0 + 76, dw - 12, 16, () -> glowStrong = !glowStrong);
            hit("opt8", cx + 6, y0 + 94, dw - 12, 16, () -> speedIdx = (speedIdx + 1) % 3);
            hit("opt9", cx + 6, y0 + 112, dw - 12, 16, () -> styleIdx = (styleIdx + 1) % 3);
            int rx = cx + dw + 8;
            hit("opt3", rx + 6, y0 + 22, dw - 12, 16, () -> sway = !sway);
            hit("opt4", rx + 6, y0 + 40, dw - 12, 16, () -> followCursor = !followCursor);
        } else {
            hit("opt10", cx + 6, y0 + 6, cw - 12, 16, () -> customOn = !customOn);
            hit("ccreset", cx + cw - 78, y0 + 104, 70, 16, () -> { customAccent = 0xFFF2762B; customAccent2 = 0xFFFFB347; });
        }
    }

    // ---------------- actions ----------------
    private String applyLabel() {
        return switch (tab) {
            case T_TRIMS -> "Copy /give command";
            case T_WARDROBE -> "Save current set";
            case T_SKIN -> "Save skin PNG";
            case T_DISCORD -> DiscordBot.isRunning() ? "Disconnect bot" : "Connect bot";
            default -> "Reset preview";
        };
    }

    private void doApply() {
        switch (tab) {
            case T_TRIMS -> copyGive();
            case T_WARDROBE -> saveLoadout();
            case T_SKIN -> saveSkin();
            case T_DISCORD -> { if (DiscordBot.isRunning()) DiscordBot.stop(); else { savePrefs(); DiscordBot.start(); } }
            default -> { viewYaw = 0.35f; status = Component.literal("Preview reset"); }
        }
    }

    private void copyGive() {
        Minecraft.getInstance().keyboardHandler.setClipboard(giveCommand());
        status = Component.literal("Copied: " + ARMOR_NAMES[armorType] + " chestplate, " + PATTERNS[pattern] + " + " + MATS[material]);
    }

    private static Path skinDir() { return FabricLoader.getInstance().getGameDir().resolve("skin-studio"); }

    private void saveSkin() {
        try {
            Path dir = skinDir();
            Files.createDirectories(dir);
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < canvas.length; i++) img.setRGB(i % 64, i / 64, canvas[i]);
            String name = "skin-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".png";
            ImageIO.write(img, "png", dir.resolve(name).toFile());
            skinDirty = false;
            status = Component.literal("Saved skin-studio/" + name + (ESSENTIAL ? "  |  Essential: Wardrobe > Skins > add from file" : ""));
            if (DiscordBot.autoPost && DiscordBot.isRunning()) sendSkinToDiscord();
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Save skin failed", e);
            status = Component.literal("Save failed: " + e.getMessage());
        }
    }

    private void openFolder() {
        try {
            Files.createDirectories(skinDir());
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), p = skinDir().toString();
            ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("explorer", p) : os.contains("mac") ? new ProcessBuilder("open", p) : new ProcessBuilder("xdg-open", p);
            pb.start();
            status = Component.literal("Opened the skin-studio folder");
        } catch (Exception e) {
            status = Component.literal("Could not open the folder: " + skinDir());
        }
    }

    private void loadSkin() {
        try {
            Path dir = skinDir();
            Files.createDirectories(dir);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().forEach(files::add);
            }
            if (files.isEmpty()) { status = Component.literal("Put a 64x64 skin PNG in the skin-studio folder of your game directory"); return; }
            fileIdx = (fileIdx + 1) % files.size();
            Path f = files.get(fileIdx);
            BufferedImage img = ImageIO.read(f.toFile());
            if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) {
                status = Component.literal(f.getFileName() + " is not a 64x64 skin");
                return;
            }
            pushUndo();
            Arrays.fill(canvas, 0);
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < 64; x++) canvas[y * 64 + x] = img.getRGB(x, y);
            skinDirty = false;
            skinChanged = true;
            status = Component.literal("Loaded " + f.getFileName() + (img.getHeight() == 32 ? " (old 64x32 format, left limbs are empty)" : ""));
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Load skin failed", e);
            status = Component.literal("Load failed: " + e.getMessage());
        }
    }

    private void pushUndo() {
        undo.add(canvas.clone());
        if (undo.size() > 40) undo.remove(0);
    }

    private void undoSkin() {
        if (undo.isEmpty()) { status = Component.literal("Nothing to undo"); return; }
        canvas = undo.remove(undo.size() - 1);
        skinDirty = true;
        skinChanged = true;
    }

    private static void fillRect(int[] c, int x, int y, int w, int h, int col) {
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) c[(y + j) * 64 + x + i] = col;
    }

    private static void fillBox(int[] c, int[] b, int col) {
        int u = b[0], v = b[1], w = b[2], h = b[3], d = b[4];
        fillRect(c, u + d, v, w, d, col); fillRect(c, u + d + w, v, w, d, col);
        fillRect(c, u, v + d, d, h, col); fillRect(c, u + d, v + d, w, h, col);
        fillRect(c, u + d + w, v + d, d, h, col); fillRect(c, u + 2 * d + w, v + d, w, h, col);
    }

    /** A simple starter skin: skin tone, hair, face, cyan shirt, blue pants. */
    private static void template(int[] c) {
        Arrays.fill(c, 0);
        int skin = 0xFFC68863, hair = 0xFF4A2F1B, shirt = 0xFF00A8A8, pants = 0xFF3C44AA, shoe = 0xFF555555;
        for (int i = 0; i < 6; i++) fillBox(c, BOXES[i], skin);
        fillBox(c, BOXES[1], shirt);
        fillRect(c, 44, 20, 4, 8, shirt); fillRect(c, 40, 20, 4, 8, shirt); fillRect(c, 48, 20, 4, 8, shirt); fillRect(c, 52, 20, 4, 8, shirt);
        fillRect(c, 36, 52, 4, 8, shirt); fillRect(c, 32, 52, 4, 8, shirt); fillRect(c, 40, 52, 4, 8, shirt); fillRect(c, 44, 52, 4, 8, shirt);
        fillRect(c, 4, 20, 4, 9, pants); fillRect(c, 0, 20, 4, 9, pants); fillRect(c, 8, 20, 4, 9, pants); fillRect(c, 12, 20, 4, 9, pants);
        fillRect(c, 20, 52, 4, 9, pants); fillRect(c, 16, 52, 4, 9, pants); fillRect(c, 24, 52, 4, 9, pants); fillRect(c, 28, 52, 4, 9, pants);
        fillRect(c, 4, 29, 4, 3, shoe); fillRect(c, 20, 61, 4, 3, shoe);
        fillRect(c, 8, 0, 8, 8, hair);
        fillRect(c, 8, 8, 8, 1, hair); fillRect(c, 0, 8, 8, 1, hair); fillRect(c, 16, 8, 8, 1, hair);
        fillRect(c, 24, 8, 8, 4, hair);
        c[12 * 64 + 9] = 0xFFFFFFFF; c[12 * 64 + 10] = 0xFF3B2F8F; c[12 * 64 + 13] = 0xFF3B2F8F; c[12 * 64 + 14] = 0xFFFFFFFF;
        c[14 * 64 + 11] = 0xFF8B4A2B; c[14 * 64 + 12] = 0xFF8B4A2B;
    }

    // ---------------- input ----------------
    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        boolean ctrl = (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && k == GLFW.GLFW_KEY_K && search != null) {
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (anyTyping()) {
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                if (search != null) search.setFocused(false);
                for (EditBox b : fields) b.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(e);
        }
        if (ctrl && k == GLFW.GLFW_KEY_Z && tab == T_SKIN) { undoSkin(); return true; }
        if (k >= GLFW.GLFW_KEY_1 && k < GLFW.GLFW_KEY_1 + TABS.length) { setTab(k - GLFW.GLFW_KEY_1); return true; }
        if (k == GLFW.GLFW_KEY_L) { setLayout(layout + 1); return true; }
        if (k == GLFW.GLFW_KEY_TAB && layout == L_RADIAL) { radialOpen = !radialOpen; init(); return true; }
        return super.keyPressed(e);
    }

    /** Polled each frame: painting, colour bars and preview rotation (no mouse event overrides needed). */
    private void pollMouse(int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        int prevMx = lastMx;
        lastMx = mx;
        boolean left = mc.mouseHandler.isLeftPressed(), right = mc.mouseHandler.isRightPressed();
        boolean pressed = left || right, edge = pressed && !wasPressed;
        wasPressed = pressed;
        if (!pressed) { canvasDrag = false; sliderDrag = -1; prevDrag = false; return; }
        if (radialOpen || tabT < 0.5f) return;

        if (tab == T_TRIMS) {
            if (edge && mx >= prevBox[0] && mx < prevBox[2] && my >= prevBox[1] && my < prevBox[3]) prevDrag = true;
            if (prevDrag) viewYaw = Math.max(-1.2f, Math.min(1.2f, viewYaw + (mx - prevMx) / 45f));
        } else if (tab == T_SKIN) {
            int cvW = 64 * cs;
            boolean inside = mx >= cvX && my >= cvY && mx < cvX + cvW && my < cvY + cvW;
            if (edge) {
                if (inside) {
                    canvasDrag = true;
                    if (tool != 3) pushUndo();
                }
                for (int i = 0; i < 3; i++) {
                    int sy = yBars() + i * 14;
                    if (mx >= rX + 12 && mx < rX + ctlW && my >= sy && my < sy + 10) sliderDrag = i;
                }
            }
            if (sliderDrag >= 0 && sliderDrag < 3) {
                int v = clampi(Math.round((mx - (rX + 12)) / (float) (ctlW - 12) * 255f), 0, 255);
                int shift = 16 - 8 * sliderDrag;
                curColor = 0xFF000000 | (curColor & ~(255 << shift) & 0xFFFFFF) | (v << shift);
            }
            if (canvasDrag && inside) {
                int x = (mx - cvX) / cs, y = (my - cvY) / cs, idx = y * 64 + x;
                if (tool == 3) {
                    if (edge && (canvas[idx] >>> 24) != 0) curColor = canvas[idx] | 0xFF000000;
                } else if (tool == 2) {
                    if (edge && !right && USED[idx]) { flood(x, y, curColor); skinDirty = true; skinChanged = true; }
                } else {
                    int nc = (right || tool == 1) ? 0 : curColor;
                    for (int by = 0; by < brush; by++) {
                        for (int bx = 0; bx < brush; bx++) {
                            int xx = x - brush / 2 + bx, yy = y - brush / 2 + by;
                            if (xx < 0 || yy < 0 || xx > 63 || yy > 63) continue;
                            int ii = yy * 64 + xx;
                            if (USED[ii] && canvas[ii] != nc) { canvas[ii] = nc; skinDirty = true; skinChanged = true; }
                        }
                    }
                }
            }
        } else if (tab == T_SETTINGS && setChip == 3) {
            if (edge) {
                for (int col = 0; col < 2; col++) {
                    for (int ch = 0; ch < 3; ch++) {
                        int[] b = colBar(col, ch);
                        if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] - 1 && my < b[1] + b[3] + 1) sliderDrag = 10 + col * 3 + ch;
                    }
                }
            }
            if (sliderDrag >= 10) {
                int id = sliderDrag - 10, col = id / 3, ch = id % 3;
                int[] b = colBar(col, ch);
                int v = clampi(Math.round((mx - b[0]) / (float) b[2] * 255f), 0, 255);
                int shift = 16 - 8 * ch;
                int cur = col == 0 ? customAccent : customAccent2;
                cur = 0xFF000000 | (cur & ~(255 << shift) & 0xFFFFFF) | (v << shift);
                if (col == 0) customAccent = cur; else customAccent2 = cur;
            }
        }
    }

    private void flood(int sx, int sy, int col) {
        int target = canvas[sy * 64 + sx];
        if (target == col) return;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(sy * 64 + sx);
        while (!stack.isEmpty()) {
            int i = stack.pop(), x = i % 64, y = i / 64;
            if (!USED[i] || canvas[i] != target) continue;
            canvas[i] = col;
            if (x > 0) stack.push(i - 1);
            if (x < 63) stack.push(i + 1);
            if (y > 0) stack.push(i - 64);
            if (y < 63) stack.push(i + 64);
        }
    }

    // ---------------- skin textures ----------------
    private void ensureTextures() {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        int size = 64 * cs;
        if (canvasTex == null || canvasSize != size) {
            if (canvasId != null) tm.release(canvasId);
            canvasImg = new NativeImage(size, size, true);
            canvasTex = new DynamicTexture(() -> "trimstudio canvas", canvasImg);
            canvasId = Identifier.fromNamespaceAndPath("packtoggles", "studio/canvas");
            tm.register(canvasId, canvasTex);
            canvasSize = size;
            texDirty = true;
        }
        if (pvTex[0] == null || pvSize != pvScale) {
            for (int k = 0; k < 2; k++) {
                if (pvId[k] != null) tm.release(pvId[k]);
                pvImg[k] = new NativeImage(16 * pvScale, 32 * pvScale, true);
                final int kk = k;
                pvTex[k] = new DynamicTexture(() -> "trimstudio preview " + kk, pvImg[k]);
                pvId[k] = Identifier.fromNamespaceAndPath("packtoggles", "studio/preview" + k);
                tm.register(pvId[k], pvTex[k]);
            }
            pvSize = pvScale;
            texDirty = true;
        }
    }

    private void rebuildTextures() {
        texDirty = false;
        int s = cs;
        for (int i = 0; i < 64 * 64; i++) {
            int x = i % 64, y = i / 64;
            int bg = USED[i] ? ((((x + y) & 1) == 0) ? 0xFF3A3030 : 0xFF2E2626) : 0xFF171212;
            int out = over(bg, canvas[i]) | 0xFF000000;
            for (int dy = 0; dy < s; dy++) for (int dx = 0; dx < s; dx++) canvasImg.setPixel(x * s + dx, y * s + dy, out);
        }
        canvasTex.upload();
        for (int k = 0; k < 2; k++) {
            int[] buf = new int[16 * 32];
            int[][] parts = k == 1 ? BACK : FRONT;
            for (int pass = 0; pass < 2; pass++) {
                for (int[] p : parts) {
                    int u = pass == 0 ? p[4] : p[6], v = pass == 0 ? p[5] : p[7];
                    for (int j = 0; j < p[3]; j++) {
                        for (int i = 0; i < p[2]; i++) {
                            int bi = (p[1] + j) * 16 + p[0] + i;
                            buf[bi] = over(buf[bi], canvas[(v + j) * 64 + u + i]);
                        }
                    }
                }
            }
            int sc = pvScale;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 16; x++) {
                    int c = buf[y * 16 + x];
                    for (int dy = 0; dy < sc; dy++) for (int dx = 0; dx < sc; dx++) pvImg[k].setPixel(x * sc + dx, y * sc + dy, c);
                }
            }
            pvTex[k].upload();
        }
    }

    private void releaseTextures() {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        if (canvasId != null) tm.release(canvasId);
        canvasId = null; canvasTex = null; canvasImg = null; canvasSize = 0;
        for (int k = 0; k < 2; k++) {
            if (pvId[k] != null) tm.release(pvId[k]);
            pvId[k] = null; pvTex[k] = null; pvImg[k] = null;
        }
        pvSize = 0;
    }

    @Override
    public void removed() {
        releaseTextures();
        super.removed();
    }

    // ---------------- 3D preview ----------------
    private void equip() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) { worldMissing = true; return; }
        worldMissing = false;
        try {
            if (dummy == null) {
                GameProfile profile = mc.player.getGameProfile();
                dummy = new RemotePlayer(level, profile);
            }
            HolderLookup.Provider ra = level.registryAccess();
            Holder<TrimPattern> pat = ra.lookupOrThrow(Registries.TRIM_PATTERN)
                    .getOrThrow(ResourceKey.create(Registries.TRIM_PATTERN, Identifier.fromNamespaceAndPath("minecraft", PATTERNS[pattern].toLowerCase(Locale.ROOT))));
            Holder<TrimMaterial> mat = ra.lookupOrThrow(Registries.TRIM_MATERIAL)
                    .getOrThrow(ResourceKey.create(Registries.TRIM_MATERIAL, Identifier.fromNamespaceAndPath("minecraft", MATS[material].toLowerCase(Locale.ROOT))));
            for (int i = 0; i < 4; i++) {
                if (!pieceOn[i]) { dummy.setItemSlot(SLOTS[i], ItemStack.EMPTY); continue; }
                ItemStack st = new ItemStack(armorItem(armorType, i));
                st.set(DataComponents.TRIM, new ArmorTrim(mat, pat));
                dummy.setItemSlot(SLOTS[i], st);
            }
        } catch (Exception ex) {
            PackTogglesClient.LOG.warn("Trim preview failed", ex);
            status = Component.literal("Preview failed: " + ex.getMessage());
        }
    }

    private static Item armorItem(int type, int slot) {
        return switch (type) {
            case 0 -> new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS}[slot];
            case 1 -> new Item[]{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS}[slot];
            case 2 -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS}[slot];
            case 3 -> new Item[]{Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS}[slot];
            case 4 -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}[slot];
            default -> new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}[slot];
        };
    }

    // ---------------- drawing ----------------
    private net.minecraft.world.entity.player.PlayerSkin skin() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getSkin() : DefaultPlayerSkin.get(mc.getUser().getProfileId());
    }

    private void icon(GuiGraphics g, int i, int x, int y, int c, int bgc) {
        switch (i) {
            case T_TRIMS -> {
                g.fill(x + 2, y, x + 12, y + 2, c); g.fill(x, y + 2, x + 14, y + 13, c);
                g.fill(x + 1, y + 5, x + 13, y + 7, bgc); g.fill(x + 3, y + 9, x + 11, y + 13, bgc);
            }
            case T_WARDROBE -> { // shirt
                g.fill(x, y + 1, x + 5, y + 5, c); g.fill(x + 9, y + 1, x + 14, y + 5, c);
                g.fill(x + 3, y + 1, x + 11, y + 14, c); g.fill(x + 5, y + 1, x + 9, y + 3, bgc);
            }
            case T_SKIN -> {
                g.fill(x + 4, y, x + 10, y + 6, c); g.fill(x + 1, y + 7, x + 13, y + 14, c);
                g.fill(x + 6, y + 8, x + 8, y + 14, bgc);
            }
            case T_DISCORD -> { // game-pad-ish chat face
                g.fill(x + 1, y + 2, x + 13, y + 12, c); g.fill(x + 3, y + 12, x + 6, y + 14, c); g.fill(x + 8, y + 12, x + 11, y + 14, c);
                g.fill(x + 3, y + 5, x + 5, y + 8, bgc); g.fill(x + 9, y + 5, x + 11, y + 8, bgc);
            }
            default -> { g.fill(x + 6, y, x + 8, y + 14, c); g.fill(x, y + 6, x + 14, y + 8, c); disc(g, x + 7, y + 7, 5, c); disc(g, x + 7, y + 7, 2, bgc); }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        partialTick = partial;
        if (dirty) {
            dirty = false;
            init();
            if (refocus && search != null) { setFocused(search); search.setFocused(true); }
            refocus = false;
        }
        long now = System.nanoTime();
        dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float adt = dt * SPEED_MUL[speedIdx];
        time += adt;
        openT = Math.min(1f, openT + adt * 3f);
        tabT = Math.min(1f, tabT + adt * 2.2f);

        int tA = customOn ? customAccent : theme.accent(), tA2 = customOn ? customAccent2 : theme.accent2();
        if (!colorsInit) { cAccent = tA; cAccent2 = tA2; cBg = theme.bg(); cCard = theme.card(); cLine = theme.line(); colorsInit = true; }
        float k = Math.min(1f, dt * 6f);
        cAccent = mix(cAccent, tA, k); cAccent2 = mix(cAccent2, tA2, k);
        cBg = mix(cBg, theme.bg(), k); cCard = mix(cCard, theme.card(), k); cLine = mix(cLine, theme.line(), k);

        pollMouse(mx, my);
        if (showcase && tab == T_TRIMS) {
            showTimer += adt;
            if (showTimer > 1.4f) { showTimer = 0; pattern = (pattern + 1) % PATTERNS.length; equipDirty = true; }
        }
        if (tab == T_TRIMS && equipDirty) { equipDirty = false; equip(); }
        if (tab == T_SKIN) {
            if (skinChanged) { skinChanged = false; texDirty = true; }
            ensureTextures();
            if (texDirty) rebuildTextures();
        }

        super.render(g, mx, my, partial);

        float pulse = Config.glow ? 0.5f + 0.5f * (float) Math.sin(time * 1.6f) : 0.5f;
        float gm = glowStrong ? 1.8f : 1f;
        for (int i = 5; i >= 1; i--) round(g, px - i, py - i, pw + 2 * i, ph + 2 * i, alpha(cAccent, (0.10f + 0.10f * pulse) * gm / i));
        round(g, px, py, pw, ph, mix(cAccent, cBg, 0.45f));
        round(g, px + 1, py + 1, pw - 2, ph - 2, alpha(cBg, STYLE_A[styleIdx]));

        g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
        if (Config.anim) {
            fx.update(adt, theme, pw, ph, Config.intensity);
            fx.draw(g, font, theme, px, py, pw, ph);
        }
        int seg = 90, sx = px + (int) ((time * 70) % (pw + seg)) - seg;
        for (int i = 0; i < seg; i += 2) {
            int xx = sx + i;
            if (xx > px && xx < px + pw) g.fill(xx, py + 1, xx + 2, py + 2, alpha(cAccent2, (i / (float) seg) * 0.9f));
        }
        g.disableScissor();

        drawNav(g);
        drawHeader(g, mx, my, partial);

        float e = eo(tabT);
        int saveCx = cx;
        cx = saveCx + (int) ((1f - e) * 28f * tabDir);
        g.enableScissor(saveCx - 2, top - 2, saveCx + cw + 2, bottom + 2);
        try {
            switch (tab) {
                case T_WARDROBE -> drawWardrobe(g);
                case T_SKIN -> drawSkin(g, mx, my);
                case T_DISCORD -> drawDiscord(g, mx, my);
                case T_SETTINGS -> drawSettings(g);
                default -> drawTrims(g, mx, my);
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
        if (classic) bold(g, "Studio", px + 42, py + 19, TEXT);

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

        if (classic) {
            int wy = py + ph - 50;
            if (wy > py + 44 + TABS.length * tabStep + 2) {
                card(g, px + 10, wy, 98, 38);
                text(g, ARMOR_NAMES[armorType], px + 17, wy + 7, muted());
                bold(g, fit(PATTERNS[pattern], 82), px + 17, wy + 21, MAT_COL[material]);
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
        float e = eo(tabT);
        int tx = cx + (layout == L_RADIAL ? 26 : 0) + (int) ((1f - e) * 14f * tabDir);
        g.drawString(font, Component.literal(TABS[tab]).withStyle(ChatFormatting.BOLD), tx, py + 12, alpha(TEXT, Math.max(0.08f, e)), false);

        if (searchActive && search != null) {
            boolean focus = search.isFocused();
            round(g, searchX - 1, py + 7, searchW + 2, 22, focus ? mix(cCard, cAccent, 0.6f) : cLine);
            round(g, searchX, py + 8, searchW, 20, mix(cCard, cBg, 0.2f));
            disc(g, searchX + 10, py + 17, 4, muted());
            disc(g, searchX + 10, py + 17, 2, mix(cCard, cBg, 0.2f));
            g.fill(searchX + 13, py + 20, searchX + 15, py + 22, muted());
            search.render(g, mx, my, partial);
        }

        round(g, stX, py + 8, stW, 20, mix(cCard, cBg, 0.2f));
        disc(g, stX + 10, py + 18, 3, pillColor());
        text(g, pillLabel(), stX + 18, py + 14, mix(TEXT, cBg, 0.15f));

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
        if (msg.isEmpty()) msg = tab == T_SKIN ? "Left click paints, right click erases  |  Ctrl+Z undo" : "1-5 tabs  |  L layout  |  Ctrl+K search";
        text(g, fit(msg, cw - applyW - 16), cx + 2, applyY + 8, muted());
        float h = ease("applyhov", hov("apply") ? 1 : 0, 14);
        round(g, applyX - 1, applyY - 1, applyW + 2, applyH + 2, alpha(cAccent2, 0.25f * h));
        round(g, applyX, applyY, applyW, applyH, mix(cAccent, cAccent2, h * 0.5f));
        String a = applyLabel();
        bold(g, a, applyX + (applyW - font.width(a) - 1) / 2, applyY + 8, cBg);
    }

    // ---- trims tab ----
    private void drawTrims(GuiGraphics g, int mx, int my) {
        int lw = trimLeftW(), h = bottom - top, dx = cx - baseCx;
        int trimC = MAT_COL[material];

        card(g, cx, top, lw, h);
        int x1 = prevBox[0] + dx, y1 = prevBox[1], x2 = prevBox[2] + dx, y2 = prevBox[3];
        if (dummy != null && !worldMissing && Minecraft.getInstance().level != null) {
            float yaw = Math.max(-1.2f, Math.min(1.2f, viewYaw + (sway && !prevDrag ? 0.45f * (float) Math.sin(time * 0.9f) : 0f)));
            float cxm = (x1 + x2) / 2f, cym = (y1 + y2) / 2f;
            float vx = followCursor ? mx : cxm - 40f * (float) Math.tan(yaw);
            float vy = followCursor ? my : cym - 12f;
            int scale = Math.max(20, (int) ((y2 - y1) * 0.42f));
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, x1, y1, x2, y2, scale, 0.0625F, vx, vy, dummy);
            } catch (Exception ex) {
                text(g, "Preview unavailable", x1 + 8, y1 + 8, RED);
            }
        } else {
            String m1 = "Join a world to";
            String m2 = "see the 3D preview";
            text(g, m1, x1 + (x2 - x1 - font.width(m1)) / 2, y1 + (y2 - y1) / 2 - 8, muted());
            text(g, m2, x1 + (x2 - x1 - font.width(m2)) / 2, y1 + (y2 - y1) / 2 + 4, muted());
        }
        text(g, fit(ARMOR_NAMES[armorType] + " | " + PATTERNS[pattern] + " | " + MATS[material], lw - 12), cx + 6, top + h - 26, TEXT);
        text(g, followCursor ? "Follows your cursor" : "Drag to rotate", cx + 6, top + h - 14, muted());

        int x0 = baseCx + trimLeftW() + 8 + dx;
        text(g, "Armor", x0, yLA, muted());
        for (int i = 0; i < rArmor.size(); i++) chip(g, "arm" + i, shifted(rArmor.get(i), dx), ARMOR_NAMES[i], armorType == i ? 1 : 0, cAccent);
        text(g, "Pieces", x0, yLB, muted());
        for (int i = 0; i < rPiece.size(); i++) {
            float on = i < 4 ? (pieceOn[i] ? 1 : 0) : (i == 4 && showcase ? 1 : 0);
            chip(g, "pc" + i, shifted(rPiece.get(i), dx), PIECE_NAMES[i], on, i < 4 ? cAccent : cAccent2);
        }
        text(g, "Material  " + MATS[material], x0, yLC, muted());
        for (int i = 0; i < rMat.size(); i++) {
            int[] r = shifted(rMat.get(i), dx);
            float hv = ease("hmat" + i, hov("mat" + i) ? 1 : 0, 14), sv = ease("smat" + i, material == i ? 1 : 0, 14);
            round(g, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, alpha(cAccent2, 0.9f * sv + 0.25f * hv));
            round(g, r[0], r[1], r[2], r[3], MAT_COL[i]);
        }
        text(g, searchText.isEmpty() ? "Pattern" : "Pattern  (filter: " + fit(searchText, 80) + ")", x0, yLD, muted());
        if (rPat.isEmpty()) text(g, "No pattern matches", x0, yLD + 14, muted());
        for (int k = 0; k < rPat.size(); k++) {
            int[] r = shifted(rPat.get(k), dx);
            if (r[1] + r[3] > bottom) continue;
            int pi = patIdx.get(k);
            chip(g, "pat" + k, r, PATTERNS[pi], pattern == pi ? 1 : 0, trimC);
        }
    }

    private static int[] shifted(int[] r, int dx) { return new int[]{r[0] + dx, r[1], r[2], r[3]}; }

    // ---- wardrobe tab ----
    private void drawWardrobe(GuiGraphics g) {
        chip(g, "wsave", new int[]{cx + 6, top, 104, 16}, "Save current set", 0, cAccent);
        int pages = Math.max(1, (wardrobe.size() + wRows - 1) / wRows);
        text(g, wardrobe.size() + " saved", cx + 118, top + 4, muted());
        chip(g, "wprev", new int[]{cx + cw - 44, top, 18, 16}, "<", 0, cAccent);
        chip(g, "wnext", new int[]{cx + cw - 24, top, 18, 16}, ">", 0, cAccent);
        String pg = (wPage + 1) + "/" + pages;
        text(g, pg, cx + cw - 50 - font.width(pg), top + 4, muted());

        card(g, cx, top + 20, cw, bottom - top - 20);
        if (wardrobe.isEmpty()) {
            text(g, "Nothing saved yet. Pick armor, material and pattern in Trims, then save the set here.", cx + 12, top + 32, muted());
            return;
        }
        for (int i = 0; i < wRows; i++) {
            int idx = wPage * wRows + i;
            if (idx >= wardrobe.size()) break;
            int[] e = entry(wardrobe.get(idx));
            if (e == null) continue;
            int ry = top + 22 + i * 22;
            int dx = (int) ((1f - stag(i)) * 24 * tabDir);
            float hv = ease("wrowhov" + i, hov("wrow" + i) ? 1 : 0, 14);
            if (hv > 0.02f) round(g, cx + 6 + dx, ry, cw - 12, 20, alpha(cAccent, 0.12f * hv));
            round(g, cx + 12 + dx, ry + 4, 12, 12, MAT_COL[e[1]]);
            bold(g, fit(PATTERNS[e[2]] + " / " + MATS[e[1]], cw - 190), cx + 30 + dx, ry + 6, TEXT);
            StringBuilder pcs = new StringBuilder();
            String[] pn = {"H", "C", "L", "B"};
            for (int k = 0; k < 4; k++) pcs.append((e[3] >> k & 1) != 0 ? pn[k] : "-");
            text(g, ARMOR_NAMES[e[0]] + "  " + pcs, cx + cw - 150 + dx, ry + 6, muted());
            boolean dh = hov("wdel" + i);
            round(g, cx + cw - 6 - 22 + dx, ry, 22, 20, dh ? RED : alpha(RED, 0.25f));
            text(g, "x", cx + cw - 6 - 14 + dx, ry + 6, TEXT);
        }
    }

    // ---- skin tab ----
    private void drawSkin(GuiGraphics g, int mx, int my) {
        int dx = cx - baseCx;
        int cvW = 64 * cs, ox = cvX + dx, oy = cvY;
        card(g, ox - 3, oy - 3, cvW + 6, cvW + 6);
        if (canvasId != null) g.blit(RenderPipelines.GUI_TEXTURED, canvasId, ox, oy, 0f, 0f, cvW, cvW, cvW, cvW);
        if (mx >= cvX && my >= cvY && mx < cvX + cvW && my < cvY + cvW) {
            int cellX = (mx - cvX) / cs, cellY = (my - cvY) / cs;
            int n = (tool == 0 || tool == 1) ? brush : 1;
            int hx = ox + (cellX - n / 2) * cs, hy = oy + (cellY - n / 2) * cs, sz = n * cs;
            g.fill(hx, hy, hx + sz, hy + 1, cAccent2); g.fill(hx, hy + sz - 1, hx + sz, hy + sz, cAccent2);
            g.fill(hx, hy, hx + 1, hy + sz, cAccent2); g.fill(hx + sz - 1, hy, hx + sz, hy + sz, cAccent2);
        }

        int rx = rX + dx;
        int tw = (ctlW - 3 * 4) / 4;
        for (int i = 0; i < 4; i++) chip(g, "tool" + i, new int[]{rx + i * (tw + 4), top, tw, 16}, TOOLS[i], tool == i ? 1 : 0, cAccent);
        int bw3 = (ctlW - 2 * 4) / 3;
        for (int i = 0; i < 3; i++) chip(g, "brush" + i, new int[]{rx + i * (bw3 + 4), yBrush(), bw3, 16}, BRUSHES[i], brush == i + 1 ? 1 : 0, cAccent2);

        String[] ch = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            int sy = yBars() + i * 14, shift = 16 - 8 * i, v = curColor >> shift & 255;
            text(g, ch[i], rx, sy + 1, muted());
            int bx = rx + 12, bw = ctlW - 12;
            g.fill(bx, sy + 1, bx + bw, sy + 9, mix(cCard, cBg, 0.6f));
            g.fill(bx, sy + 1, bx + Math.round(bw * v / 255f), sy + 9, i == 0 ? 0xFFE0564F : i == 1 ? 0xFF58D26F : 0xFF378ADD);
            int kx = bx + Math.round((bw - 3) * v / 255f);
            g.fill(kx, sy, kx + 3, sy + 10, 0xFFFFFFFF);
        }
        round(g, rx, yHex(), 22, 16, curColor);
        text(g, String.format("#%06X", curColor & 0xFFFFFF), rx + 28, yHex() + 4, TEXT);

        for (int i = 0; i < 16; i++) {
            int sx = rx + (i % 8) * (palSw + 3), sy = yPal() + (i / 8) * (palSw + 3);
            boolean sel = (PALETTE[i] & 0xFFFFFF) == (curColor & 0xFFFFFF);
            round(g, sx - 1, sy - 1, palSw + 2, palSw + 2, sel ? 0xFFFFFFFF : hov("pal" + i) ? mix(cLine, TEXT, 0.5f) : cLine);
            g.fill(sx, sy, sx + palSw, sy + palSw, PALETTE[i]);
        }
        int by = yPal() + 2 * (palSw + 3) + 6, bw = (ctlW - 4) / 2;
        String[] bl = {"Undo", "Template", "Load next", "Clear", "Open folder", "To Discord"};
        String[] bk = {"undo", "tmpl", "load", "clear", "folder", "sdisc"};
        for (int i = 0; i < 6; i++) chip(g, bk[i], new int[]{rx + (i % 2) * (bw + 4), by + (i / 2) * 22, bw, 18}, bl[i], 0, cAccent);

        int pvx = rx + ctlW + 8, pvW = 16 * pvScale, pvH = 32 * pvScale;
        card(g, pvx - 3, top - 3, 2 * pvW + 8 + 6, pvH + 22);
        if (pvId[0] != null) g.blit(RenderPipelines.GUI_TEXTURED, pvId[0], pvx, top, 0f, 0f, pvW, pvH, pvW, pvH);
        if (pvId[1] != null) g.blit(RenderPipelines.GUI_TEXTURED, pvId[1], pvx + pvW + 8, top, 0f, 0f, pvW, pvH, pvW, pvH);
        text(g, "Front", pvx + (pvW - font.width("Front")) / 2, top + pvH + 5, muted());
        text(g, "Back", pvx + pvW + 8 + (pvW - font.width("Back")) / 2, top + pvH + 5, muted());
    }

    // ---- discord tab ----
    private void field(GuiGraphics g, EditBox b, int x, int y, int w, String label, int mx, int my) {
        text(g, label, x, y - 10, muted());
        round(g, x, y, w, 18, b.isFocused() ? mix(cCard, cAccent, 0.6f) : cLine);
        round(g, x + 1, y + 1, w - 2, 16, mix(cCard, cBg, 0.25f));
        b.setX(x + 5);
        b.setY(y + 4);
        b.render(g, mx, my, partialTick);
    }

    private void drawDiscord(GuiGraphics g, int mx, int my) {
        int lw = discLeftW(), fx0 = cx + 12, fw = lw - 24;
        card(g, cx, top, lw, bottom - top);
        bold(g, "Discord bot", cx + 10, top + 7, TEXT);
        boolean on = DiscordBot.isRunning();
        disc(g, cx + lw - 14, top + 11, 3, on ? GREEN : muted());
        text(g, fit(DiscordBot.status(), lw - 24), cx + 10, top + 19, on ? GREEN : muted());
        if (tokenBox != null) field(g, tokenBox, fx0, top + 43, fw, "Bot token (kept on this PC)", mx, my);
        if (chanBox != null) field(g, chanBox, fx0, top + 73, fw, "Channel ID", mx, my);
        if (userBox != null) field(g, userBox, fx0, top + 103, fw, "Only obey this user ID (optional)", mx, my);
        optionRow(g, 5, cx + 6, top + 123, lw - 12, "Listen for !commands", null, DiscordBot.listen);
        optionRow(g, 6, cx + 6, top + 141, lw - 12, "Post saved skins", null, DiscordBot.autoPost);
        int bw = (lw - 24 - 8) / 3, by = top + 163;
        chip(g, "dcon", new int[]{fx0, by, bw, 18}, on ? "Disconnect" : "Connect", on ? 1 : 0, RED);
        chip(g, "dtrim", new int[]{fx0 + bw + 4, by, bw, 18}, "Send trim", 0, cAccent);
        chip(g, "dskin", new int[]{fx0 + 2 * (bw + 4), by, bw, 18}, "Send skin", 0, cAccent);

        int rx = cx + lw + 8, rw = cw - lw - 8, rh = bottom - top;
        card(g, rx, top, rw, rh);
        bold(g, "Activity", rx + 10, top + 7, TEXT);
        List<String> log = DiscordBot.log();
        int lines = Math.max(1, (rh - 70) / 10);
        int startIdx = Math.max(0, log.size() - lines);
        if (log.isEmpty()) text(g, "Nothing yet", rx + 10, top + 22, muted());
        for (int i = startIdx; i < log.size(); i++) text(g, fit(log.get(i), rw - 20), rx + 10, top + 22 + (i - startIdx) * 10, TEXT);
        int hy = top + rh - 46;
        text(g, "Commands in the channel:", rx + 10, hy, muted());
        text(g, fit("!trim  !skin  !random  !help", rw - 20), rx + 10, hy + 11, TEXT);
        text(g, fit("!set <armor> <pattern> <material>", rw - 20), rx + 10, hy + 22, TEXT);
        text(g, fit("Enable Message Content Intent on the bot", rw - 20), rx + 10, hy + 33, muted());
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
        else if (setChip == 2) drawDisplayPage(g, y0);
        else drawColorsPage(g, y0);
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
        text(g, "Where the navigation lives. Press L anywhere to cycle.", cx + 4, y0 - 2, muted());
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
            int x = cx + 6 + (i % 4) * (gw + 6), y = y0 + (i / 4) * (cellH + 4) + (int) ((1f - stag(i / 4 + i % 4)) * 12f);
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
        int dw = dispCardW(), ch = 136;
        int rx = cx + dw + 8;
        int la = (int) ((1f - stag(0)) * 14f), lb = (int) ((1f - stag(1)) * 14f);

        card(g, cx, y0 + la, dw, ch);
        bold(g, "Display and effects", cx + 8, y0 + 7 + la, TEXT);
        optionRow(g, 0, cx + 6, y0 + 22 + la, dw - 12, "Animations", null, Config.anim);
        optionRow(g, 1, cx + 6, y0 + 40 + la, dw - 12, "Particle amount", INTENSITY[Config.intensity], true);
        optionRow(g, 2, cx + 6, y0 + 58 + la, dw - 12, "Glow pulse", null, Config.glow);
        optionRow(g, 7, cx + 6, y0 + 76 + la, dw - 12, "Glow strength", GLOWS[glowStrong ? 1 : 0], true);
        optionRow(g, 8, cx + 6, y0 + 94 + la, dw - 12, "Animation speed", SPEED[speedIdx], true);
        optionRow(g, 9, cx + 6, y0 + 112 + la, dw - 12, "Panel style", STYLE[styleIdx], true);

        card(g, rx, y0 + lb, dw, ch);
        bold(g, "3D preview", rx + 8, y0 + 7 + lb, TEXT);
        optionRow(g, 3, rx + 6, y0 + 22 + lb, dw - 12, "Idle sway", null, sway);
        optionRow(g, 4, rx + 6, y0 + 40 + lb, dw - 12, "Follow cursor", null, followCursor);
        bold(g, "Integrations", rx + 8, y0 + 64 + lb, TEXT);
        text(g, fit(ESSENTIAL ? "Essential: installed" : "Essential: not installed", dw - 16), rx + 8, y0 + 78 + lb, ESSENTIAL ? GREEN : muted());
        text(g, fit("Discord: " + DiscordBot.status(), dw - 16), rx + 8, y0 + 90 + lb, DiscordBot.isRunning() ? GREEN : muted());
        text(g, fit("Skins: skin-studio folder", dw - 16), rx + 8, y0 + 102 + lb, muted());
    }

    private void drawColorsPage(GuiGraphics g, int y0) {
        int dx = cx - baseCx, half = (cw - 16) / 2;
        card(g, cx, y0, cw, 126);
        optionRow(g, 10, cx + 6, y0 + 6, cw - 12, "Use custom colors instead of the theme", null, customOn);
        String[] titles = {"Accent", "Accent 2"};
        for (int col = 0; col < 2; col++) {
            int gx = cx + 8 + col * half;
            text(g, titles[col], gx, y0 + 32, muted());
            int cur = col == 0 ? customAccent : customAccent2;
            round(g, gx + 50, y0 + 28, 22, 14, cur);
            text(g, String.format("#%06X", cur & 0xFFFFFF), gx + 78, y0 + 32, TEXT);
            for (int ch = 0; ch < 3; ch++) {
                int[] b = colBar(col, ch);
                int bx = b[0] + dx, by = b[1], v = cur >> (16 - 8 * ch) & 255;
                text(g, "RGB".substring(ch, ch + 1), bx - 12, by + 1, muted());
                g.fill(bx, by, bx + b[2], by + b[3], mix(cCard, cBg, 0.6f));
                g.fill(bx, by, bx + Math.round(b[2] * v / 255f), by + b[3], ch == 0 ? 0xFFE0564F : ch == 1 ? 0xFF58D26F : 0xFF378ADD);
                int kx = bx + Math.round((b[2] - 3) * v / 255f);
                g.fill(kx, by - 1, kx + 3, by + b[3] + 1, 0xFFFFFFFF);
            }
        }
        chip(g, "ccreset", new int[]{cx + cw - 78, y0 + 104, 70, 16}, "Reset", 0, cAccent);
        text(g, customOn ? "Custom colors are on" : "Turn on to override the theme accent", cx + 8, y0 + 108, muted());
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
        savePrefs();
        Minecraft.getInstance().setScreen(parent);
    }
}
