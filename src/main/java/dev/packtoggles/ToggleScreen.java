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
import net.minecraft.client.multiplayer.PlayerInfo;
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
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.Color;
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
 * Trim Studio: 3D armor-trim preview, saved loadouts (Wardrobe), waypoints, voice chat info, a Discord bot
 * and settings. Everything is drawn by hand in render(); vanilla Buttons are transparent click areas. Mouse dragging
 * (sliders, preview rotation) is polled each frame.
 */
public class ToggleScreen extends Screen {
    private static final int TEXT = 0xFFF5E8DD, GREEN = 0xFF58D26F, RED = 0xFFE0564F, AMBER = 0xFFF0B429;
    private static final String[] TABS = {"Trims", "Wardrobe", "Waypoints", "Discord", "Players", "Combo", "Skin", "Stats", "Voice Chat", "Training", "Settings"};
    private static final int T_TRIMS = 0, T_WARDROBE = 1, T_WAYPOINTS = 2, T_DISCORD = 3, T_PLAYERS = 4, T_COMBO = 5, T_SKIN = 6, T_STATS = 7, T_VOICE = 8, T_TRAIN = 9, T_SETTINGS = 10;
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

    // session + saved state
    private static int layout = L_RAIL;
    private static int armorType = 5, material = 0, pattern = 0;
    private static final boolean[] pieceOn = {true, true, true, true};
    private static boolean showcase = false, sway = true, followCursor = false, glowStrong = false;
    private static int speedIdx = 1, styleIdx = 0;
    private static boolean customOn = false;
    private static int customAccent = 0xFFF2762B, customAccent2 = 0xFFFFB347;
    private static float viewYaw = 0.35f;
    private static boolean equipDirty = true;
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
    private EditBox search, tokenBox, chanBox, userBox, chatBox, chatChanBox;
    private int discPage = 0;   // 0 = bot, 1 = chat (both live on the Discord tab)
    private String chatText = "";
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

    // shared mouse state (colour sliders)
    private boolean wasPressed = false;
    private int sliderDrag = -1;

    // wardrobe tab
    private int wRows = 1;

    public ToggleScreen(Screen parent) {
        super(Component.translatable("screen.packtoggles.title"));
        this.parent = parent;
        loadPrefs();
        PackTogglesClient.LOG.info("[Trim Studio] screen opened: build 3.1.0, layout={}, essential={}", LAYOUTS[layout], ESSENTIAL);
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
        DiscordBot.token = p.getProperty("dc.token", "");
        DiscordBot.channelId = p.getProperty("dc.channel", "");
        DiscordBot.userId = p.getProperty("dc.user", "");
        DiscordBot.listen = getb(p, "dc.listen", true);
        DiscordBot.autoPost = getb(p, "dc.autopost", false);
        DiscordBot.relay = getb(p, "dc.relay", false);
        DiscordBot.chatChannelId = p.getProperty("dc.chatchannel", "");
        DiscordBot.logChat = getb(p, "dc.logchat", false);
        DiscordBot.hourly = getb(p, "dc.hourly", false);
        DiscordBot.sayOn = getb(p, "dc.say", true);
        DiscordBot.chatEmbeds = getb(p, "dc.embeds", true);
        DiscordBot.lockToken = getb(p, "dc.lock", false);
        DiscordBot.autoConnect = getb(p, "dc.auto", true);
        DiscordBot.scriptsOn = getb(p, "dc.scripts", true);
        Extras.wpHud = getb(p, "wp.hud", true);
        Extras.wpDeath = getb(p, "wp.death", true);
        Extras.comboHud = getb(p, "combo.hud", true);
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
        p.setProperty("dc.token", DiscordBot.token);
        p.setProperty("dc.channel", DiscordBot.channelId);
        p.setProperty("dc.user", DiscordBot.userId);
        p.setProperty("dc.listen", "" + DiscordBot.listen);
        p.setProperty("dc.autopost", "" + DiscordBot.autoPost);
        p.setProperty("dc.relay", "" + DiscordBot.relay);
        p.setProperty("dc.chatchannel", DiscordBot.chatChannelId);
        p.setProperty("dc.logchat", "" + DiscordBot.logChat);
        p.setProperty("dc.hourly", "" + DiscordBot.hourly);
        p.setProperty("dc.say", "" + DiscordBot.sayOn);
        p.setProperty("dc.embeds", "" + DiscordBot.chatEmbeds);
        p.setProperty("dc.lock", "" + DiscordBot.lockToken);
        p.setProperty("dc.auto", "" + DiscordBot.autoConnect);
        p.setProperty("dc.scripts", "" + DiscordBot.scriptsOn);
        p.setProperty("wp.hud", "" + Extras.wpHud);
        p.setProperty("wp.death", "" + Extras.wpDeath);
        p.setProperty("combo.hud", "" + Extras.comboHud);
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

    // ---- Discord !players / !waypoints / !mark ----
    /** Online players from the tab list. Call on the game thread. */
    static String playersText() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return "Not in a world or server right now";
        List<String> names = new ArrayList<>();
        for (PlayerInfo i : mc.getConnection().getOnlinePlayers()) names.add(i.getProfile().name());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) return "No players online";
        return "**Players online (" + names.size() + "):** " + String.join(", ", names);
    }

    private static Path waypointPath() { return FabricLoader.getInstance().getConfigDir().resolve("trim-studio-waypoints.txt"); }

    /** Each line: name TAB x TAB y TAB z TAB dimension. */
    static List<String[]> loadWaypoints() {
        List<String[]> out = new ArrayList<>();
        try {
            if (!Files.exists(waypointPath())) return out;
            for (String line : Files.readAllLines(waypointPath())) {
                String[] t = line.split("\t");
                if (t.length >= 5) out.add(t);
            }
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Could not read waypoints", e);
        }
        return out;
    }

    static String waypointsText() {
        List<String[]> l = loadWaypoints();
        if (l.isEmpty()) return "No waypoints yet. Use `!mark <name>` while in game.";
        StringBuilder sb = new StringBuilder("**Waypoints (" + l.size() + "):**");
        for (String[] w : l) sb.append("\n").append(w[0]).append(": ").append(w[1]).append(" ").append(w[2]).append(" ").append(w[3]).append(" (").append(w[4]).append(")");
        return sb.toString();
    }

    /** Saves the player's current position as a waypoint. Call on the game thread. */
    static String markHere(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "Join a world first";
        List<String[]> l = loadWaypoints();
        if (l.size() >= 100) return "Waypoint list is full (100)";
        String n = name == null ? "" : name.replaceAll("[\\t\\r\\n]+", " ").trim();
        if (n.length() > 32) n = n.substring(0, 32);
        if (n.isEmpty()) n = "Waypoint " + (l.size() + 1);
        String dim = Extras.dimension(mc);
        long x = Math.round(mc.player.getX()), y = Math.round(mc.player.getY()), z = Math.round(mc.player.getZ());
        l.add(new String[]{n, "" + x, "" + y, "" + z, dim, "" + (l.size() % Extras.WP_COL.length), "1"});
        try {
            List<String> lines = new ArrayList<>();
            for (String[] w : l) lines.add(String.join("\t", w));
            Files.write(waypointPath(), lines);
            Extras.wpAt = 0;
        } catch (Exception e) {
            return "Could not save the waypoint: " + e.getMessage();
        }
        return "Marked **" + n + "** at " + x + " " + y + " " + z + " (" + dim + ")";
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

    private Button hit(String key, int x, int y, int w, int h, Runnable r) {
        Button b = Button.builder(Component.empty(), btn -> r.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f);
        addRenderableWidget(b);
        hits.put(key, b);
        return b;
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

    private String pillLabel() { return DiscordBot.isRunning() ? "Bot online" : "Ready"; }

    private int pillColor() { return GREEN; }

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
        aimBtn = null;
        tokenBox = chanBox = userBox = chatBox = chatChanBox = wpNameBox = scriptBox = scriptNameBox = skinHexBox = null;
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
        stW = Math.max(font.width("Bot online"), font.width("Ready")) + 26;
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
            case T_WAYPOINTS -> initWaypoints();
            case T_DISCORD -> initDiscordTab();
            case T_PLAYERS -> initPlayers();
            case T_COMBO -> initCombo();
            case T_SKIN -> initSkin();
            case T_VOICE -> initVoice();
            case T_TRAIN -> initTrain();
            case T_SETTINGS -> initSettings();
            default -> { }
        }
    }

    private EditBox wpNameBox, scriptBox, scriptNameBox, skinHexBox;
    private String wpName = "", scriptText = "", scriptName = "";

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

    // ---- discord tab layout ----
    private int discLeftW() { return Math.max(190, Math.min(260, cw * 11 / 20)); }

    private EditBox makeBox(int x, int y, int w, String value, Consumer<String> onChange, boolean mask) {
        EditBox b = new EditBox(font, x + 5, y + 4, w - 10, 12, Component.literal("field"));
        b.setBordered(false);
        b.setMaxLength(120);
        if (mask) b.addFormatter((s, i) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
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
        tokenBox = makeBox(fx0, top + 43, fw, DiscordBot.token, s -> { if (!DiscordBot.lockToken) DiscordBot.token = s.trim(); }, true);
        tokenBox.setEditable(!DiscordBot.lockToken);
        chanBox = makeBox(fx0, top + 73, fw, DiscordBot.channelId, s -> DiscordBot.channelId = s.trim(), false);
        userBox = makeBox(fx0, top + 103, fw, DiscordBot.userId, s -> DiscordBot.userId = s.trim(), false);
        hit("opt5", cx + 6, top + 123, lw - 12, 16, () -> DiscordBot.listen = !DiscordBot.listen);
        int bw = (lw - 24 - 4) / 2, by = top + 163;
        hit("dcon", fx0, by, bw, 18, () -> {
            if (DiscordBot.isRunning()) DiscordBot.stop(); else { savePrefs(); DiscordBot.start(); }
        });
        hit("dtrim", fx0 + bw + 4, by, bw, 18, () -> DiscordBot.sendText(loadoutText()));
        hit("opt11", cx + 6, top + 185, lw - 12, 16, () -> DiscordBot.relay = !DiscordBot.relay);
        chatBox = makeBox(fx0, top + 221, fw - 52, chatText, s -> chatText = s, false);
        hit("dchat", fx0 + fw - 48, top + 221, 48, 18, this::sendChat);
    }

    // ---- discord tab: Bot / Chat pages ----
    private static final String[] DISC_PAGES = {"Bot", "Chat", "Scripts"};

    private void initDiscordTab() {
        int chipX = cx + 8;
        for (int i = 0; i < DISC_PAGES.length; i++) {
            final int idx = i;
            int w = font.width(DISC_PAGES[i]) + 14;
            hit("dchip" + i, chipX, top, w, 16, () -> { discPage = idx; restartEntrance(1); init(); });
            chipX += w + 4;
        }
        top += 22;
        try { if (discPage == 0) initDiscord(); else if (discPage == 1) initChat(); else initScripts(); } finally { top -= 22; }
    }

    private void drawDiscordTab(GuiGraphics g, int mx, int my) {
        int chipX = cx + 8;
        for (int i = 0; i < DISC_PAGES.length; i++) {
            int w = font.width(DISC_PAGES[i]) + 14;
            float sel = ease("dchip" + i, discPage == i ? 1 : 0, 14);
            round(g, chipX, top, w, 16, mix(cCard, cAccent, 0.15f + 0.8f * sel));
            text(g, DISC_PAGES[i], chipX + 7, top + 4, mix(TEXT, cBg, sel));
            chipX += w + 4;
        }
        top += 22;
        try { if (discPage == 0) drawDiscord(g, mx, my); else if (discPage == 1) drawChat(g, mx, my); else drawScripts(g, mx, my); } finally { top -= 22; }
    }

    // ---- voice chat tab (needs the Simple Voice Chat mod to tell who has it) ----
    private static int voicePage = 0;
    private static boolean vcTried = false;
    private static java.lang.reflect.Method vcMgr, vcGet, vcDis, vcDisc;
    private static final boolean VOICE_MOD = FabricLoader.getInstance().isModLoaded("voicechat");

    /** 0 = can't tell, 1 = no voice chat, 2 = voice chat on, 3 = voice chat off (muted, disabled or disconnected). */
    private static int voiceState(UUID id) {
        if (!VOICE_MOD) return 0;
        try {
            if (!vcTried) {
                vcTried = true;
                Class<?> cm = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
                java.lang.reflect.Method m1 = cm.getMethod("getPlayerStateManager");
                m1.setAccessible(true);
                java.lang.reflect.Method m2 = m1.getReturnType().getMethod("getState", UUID.class);
                m2.setAccessible(true);
                Class<?> ps = m2.getReturnType();
                java.lang.reflect.Method m3 = ps.getMethod("isDisabled");
                m3.setAccessible(true);
                java.lang.reflect.Method m4 = ps.getMethod("isDisconnected");
                m4.setAccessible(true);
                vcMgr = m1; vcGet = m2; vcDis = m3; vcDisc = m4;
            }
            if (vcMgr == null) return 0;
            Object mgr = vcMgr.invoke(null);
            if (mgr == null) return 0;
            Object st = vcGet.invoke(mgr, id);
            if (st == null) return 1;
            boolean off = (Boolean) vcDis.invoke(st) || (Boolean) vcDisc.invoke(st);
            return off ? 3 : 2;
        } catch (Throwable t) {
            return 0;
        }
    }

    private List<net.minecraft.world.entity.player.Player> nearbyPlayers() {
        Minecraft mc = Minecraft.getInstance();
        List<net.minecraft.world.entity.player.Player> l = new ArrayList<>();
        if (mc.level == null || mc.player == null) return l;
        for (net.minecraft.world.entity.player.Player p : mc.level.players()) if (p != mc.player) l.add(p);
        l.sort(Comparator.comparingDouble(p -> mc.player.distanceToSqr(p)));
        return l;
    }

    private int voiceRows() { return Math.max(1, (bottom - top - 44) / 18); }

    private void initVoice() {
        int pages = Math.max(1, (nearbyPlayers().size() + voiceRows() - 1) / voiceRows());
        voicePage = clampi(voicePage, 0, pages - 1);
        hit("vprev", cx + cw - 46, top + 4, 18, 16, () -> voicePage = (voicePage + pages - 1) % pages);
        hit("vnext", cx + cw - 24, top + 4, 18, 16, () -> voicePage = (voicePage + 1) % pages);
    }

    private void drawVoice(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        card(g, cx, top, cw, bottom - top);
        List<net.minecraft.world.entity.player.Player> all = nearbyPlayers();
        int rows = voiceRows(), pages = Math.max(1, (all.size() + rows - 1) / rows);
        voicePage = clampi(voicePage, 0, pages - 1);
        bold(g, "Players near you (" + all.size() + ")", cx + 10, top + 8, TEXT);
        String pg = (voicePage + 1) + "/" + pages;
        text(g, pg, cx + cw - 50 - font.width(pg), top + 8, muted());
        chip(g, "vprev", new int[]{cx + cw - 46, top + 4, 18, 16}, "<", 0, cAccent);
        chip(g, "vnext", new int[]{cx + cw - 24, top + 4, 18, 16}, ">", 0, cAccent);
        text(g, fit(VOICE_MOD ? "Simple Voice Chat is installed" : "Simple Voice Chat is not installed, so I can't tell who has it", cw - 24),
                cx + 12, top + 24, VOICE_MOD ? GREEN : AMBER);
        if (all.isEmpty()) { text(g, "No other players nearby", cx + 12, top + 42, muted()); return; }
        for (int i = 0; i < rows; i++) {
            int idx = voicePage * rows + i;
            if (idx >= all.size()) break;
            net.minecraft.world.entity.player.Player p = all.get(idx);
            int ry = top + 38 + i * 18;
            round(g, cx + 8, ry, cw - 16, 16, alpha(cAccent, 0.06f));
            try { PlayerFaceRenderer.draw(g, skinFor(p.getUUID()), cx + 12, ry + 2, 12); } catch (Exception ignored) { }
            text(g, fit(p.getName().getString(), Math.max(60, cw / 3)), cx + 28, ry + 4, TEXT);
            text(g, String.format(Locale.ROOT, "%.0f m", mc.player.distanceTo(p)), cx + 28 + Math.max(60, cw / 3) + 6, ry + 4, muted());
            int s = voiceState(p.getUUID());
            String label = s == 2 ? "Voice chat: ON" : s == 3 ? "Voice chat: OFF" : s == 1 ? "No voice chat" : "Unknown";
            int col = s == 2 ? GREEN : s == 3 ? AMBER : s == 1 ? RED : muted();
            text(g, label, cx + cw - 14 - font.width(label), ry + 4, col);
        }
    }

    // ---- combo tab ----
    private void initCombo() {
        hit("opt19", cx + 6, top + 22, cw - 12, 16, () -> { Extras.comboHud = !Extras.comboHud; savePrefs(); });
    }

    private void drawCombo(GuiGraphics g, int mx, int my) {
        card(g, cx, top, cw, bottom - top);
        bold(g, "Combo tracker", cx + 10, top + 6, TEXT);
        optionRow(g, 19, cx + 6, top + 22, cw - 12, "Show combo on screen", null, Extras.comboHud);
        String[] labels = {"Combo", "Best", "Hits", "Taken"};
        int[] vals = {Extras.combo, Extras.bestCombo, Extras.hits, Extras.taken};
        int tw = (cw - 12 - 18) / 4;
        for (int i = 0; i < 4; i++) {
            int tx = cx + 6 + i * (tw + 6), ty = top + 44;
            round(g, tx, ty, tw, 34, alpha(cAccent, 0.08f));
            text(g, labels[i], tx + 8, ty + 6, muted());
            bold(g, "" + vals[i], tx + 8, ty + 19, i == 0 && vals[i] > 0 ? cAccent2 : TEXT);
        }
        int total = Extras.hits + Extras.taken;
        String ratio = total == 0 ? "-" : Math.round(100f * Extras.hits / total) + "% of fights landed";
        text(g, fit("Last target: " + (Extras.lastTarget.isEmpty() ? "-" : Extras.lastTarget) + "   " + ratio, cw - 24), cx + 12, top + 86, muted());
        text(g, "A combo is hits in a row with under 2 s between them and no damage taken", cx + 12, top + 98, muted());
        bold(g, "Recent combos", cx + 12, top + 114, TEXT);
        int y = top + 128, n = 0;
        for (int c : Extras.history) {
            if (y > bottom - 12) break;
            text(g, "x" + c, cx + 14, y, c >= 5 ? cAccent2 : TEXT);
            int bw = Math.min(cw - 80, c * 8);
            round(g, cx + 44, y + 1, Math.max(4, bw), 6, alpha(cAccent, 0.5f));
            y += 11;
            n++;
        }
        if (n == 0) text(g, "Nothing yet. Hit something twice in a row", cx + 14, y, muted());
    }

    // ---- skin editor tab ----
    private static BufferedImage skinImg;
    private static volatile BufferedImage skinLoaded;
    private static volatile boolean skinLoading = false;
    private static final Deque<BufferedImage> skinUndo = new ArrayDeque<>();
    private static int skinColor = 0xFFE0564F;
    private static final int[] SKIN_PAL = {0xFF000000, 0xFF4A4A4A, 0xFF9A9A9A, 0xFFFFFFFF, 0xFFE0564F, 0xFFF2762B, 0xFFF0B429, 0xFFFFE066,
            0xFF58D26F, 0xFF1BC9B0, 0xFF4FB3E8, 0xFF6C8CFF, 0xFF9B5CFF, 0xFFFF8FB8, 0xFF8B5A2B, 0xFFE8B98A};
    private int skinTool = 0;
    private String skinHex = "E0564F";
    private final int[] skinBox = new int[4];
    private int skinScale = 2;

    private static Path skinPath() { return FabricLoader.getInstance().getConfigDir().resolve("trim-studio-skin.png"); }

    private static BufferedImage copyOfSkin(BufferedImage src) {
        BufferedImage o = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        if (src != null) for (int y = 0; y < Math.min(64, src.getHeight()); y++) for (int x = 0; x < Math.min(64, src.getWidth()); x++) o.setRGB(x, y, src.getRGB(x, y));
        return o;
    }

    private static void ensureSkin() {
        if (skinImg != null) return;
        try {
            if (Files.exists(skinPath())) { BufferedImage i = ImageIO.read(skinPath().toFile()); if (i != null) { skinImg = copyOfSkin(i); return; } }
        } catch (Exception ignored) { }
        skinImg = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    }

    private static void pushUndo() {
        skinUndo.addLast(copyOfSkin(skinImg));
        while (skinUndo.size() > 30) skinUndo.removeFirst();
    }

    private void loadMySkin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { status = Component.literal("Join a world to load your skin"); return; }
        String hash = ChatBridge.hashFromProfile(mc.player.getGameProfile());
        if (hash == null) { status = Component.literal("No custom skin found for this account (default skin)"); return; }
        if (skinLoading) return;
        skinLoading = true;
        status = Component.literal("Loading your skin...");
        final String h = hash.toLowerCase(Locale.ROOT);
        Thread t = new Thread(() -> {
            try {
                BufferedImage i = ChatBridge.skinImage(h);
                if (i != null) skinLoaded = copyOfSkin(i);
            } finally {
                skinLoading = false;
            }
        }, "trimstudio-skin");
        t.setDaemon(true);
        t.start();
    }

    private void saveSkin() {
        ensureSkin();
        try {
            ImageIO.write(skinImg, "png", skinPath().toFile());
            status = Component.literal("Saved skin PNG. Use !skin in Discord to get it, or upload it on minecraft.net");
        } catch (Exception e) {
            status = Component.literal("Could not save the skin: " + e.getMessage());
        }
    }

    private void initSkin() {
        ensureSkin();
        int lw = skinCardW(), rx = cx + lw + 8, rw = cw - lw - 8;
        int sw = Math.max(8, Math.min(16, (rw - 20) / 8));
        for (int i = 0; i < SKIN_PAL.length; i++) {
            final int c = SKIN_PAL[i];
            hit("skpal" + i, rx + 10 + (i % 8) * (sw + 1), top + 24 + (i / 8) * (sw + 1), sw, sw, () -> {
                skinColor = c;
                skinHex = String.format("%06X", c & 0xFFFFFF);
                skinTool = 0;
                init();
            });
        }
        skinHexBox = makeBox(rx + 10, top + 74, rw - 20, skinHex, s -> {
            skinHex = s;
            String h = s.trim().replace("#", "");
            if (h.matches("[0-9a-fA-F]{6}")) skinColor = 0xFF000000 | Integer.parseInt(h, 16);
        }, false);
        skinHexBox.setMaxLength(7);
        int cwid = (rw - 20 - 8) / 3;
        hit("sktool0", rx + 10, top + 98, cwid, 16, () -> skinTool = 0);
        hit("sktool1", rx + 14 + cwid, top + 98, cwid, 16, () -> skinTool = 1);
        hit("sktool2", rx + 18 + 2 * cwid, top + 98, cwid, 16, () -> skinTool = 2);
        hit("skload", rx + 10, top + 118, cwid, 16, this::loadMySkin);
        hit("skundo", rx + 14 + cwid, top + 118, cwid, 16, () -> { if (!skinUndo.isEmpty()) skinImg = skinUndo.removeLast(); });
        hit("skclear", rx + 18 + 2 * cwid, top + 118, cwid, 16, () -> { pushUndo(); skinImg = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); });
    }

    private int skinCardW() {
        skinScale = clampi(Math.min((cw - 190) / 64, (bottom - top - 28) / 64), 1, 4);
        return 64 * skinScale + 16;
    }

    private void skinPaint(int mx, int my, boolean left, boolean right, boolean edge) {
        if (skinImg == null) return;
        if (mx < skinBox[0] || my < skinBox[1] || mx >= skinBox[0] + 64 * skinScale || my >= skinBox[1] + 64 * skinScale) return;
        int px = (mx - skinBox[0]) / skinScale, py = (my - skinBox[1]) / skinScale;
        if (edge) pushUndo();
        if (right || (left && skinTool == 1)) skinImg.setRGB(px, py, 0);
        else if (left && skinTool == 2) {
            int c = skinImg.getRGB(px, py);
            if ((c >>> 24) != 0) { skinColor = c | 0xFF000000; skinHex = String.format("%06X", c & 0xFFFFFF); if (skinHexBox != null) skinHexBox.setValue(skinHex); }
        } else if (left) skinImg.setRGB(px, py, skinColor | 0xFF000000);
    }

    private void drawSkin(GuiGraphics g, int mx, int my) {
        ensureSkin();
        BufferedImage fresh = skinLoaded;
        if (fresh != null) { skinLoaded = null; pushUndo(); skinImg = fresh; status = Component.literal("Loaded your skin"); }
        int lw = skinCardW(), rx = cx + lw + 8, rw = cw - lw - 8;
        card(g, cx, top, lw, bottom - top);
        skinBox[0] = cx + 8; skinBox[1] = top + 8; skinBox[2] = 64 * skinScale; skinBox[3] = 64 * skinScale;
        g.fill(skinBox[0], skinBox[1], skinBox[0] + skinBox[2], skinBox[1] + skinBox[3], 0xFF22252B);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int c = skinImg.getRGB(x, y);
                if ((c >>> 24) == 0) continue;
                g.fill(skinBox[0] + x * skinScale, skinBox[1] + y * skinScale, skinBox[0] + (x + 1) * skinScale, skinBox[1] + (y + 1) * skinScale, c | 0xFF000000);
            }
        }
        if (mx >= skinBox[0] && my >= skinBox[1] && mx < skinBox[0] + skinBox[2] && my < skinBox[1] + skinBox[3]) {
            int px = (mx - skinBox[0]) / skinScale, py = (my - skinBox[1]) / skinScale;
            int hx = skinBox[0] + px * skinScale, hy = skinBox[1] + py * skinScale;
            g.fill(hx, hy, hx + skinScale, hy + 1, 0xFFFFFFFF); g.fill(hx, hy + skinScale - 1, hx + skinScale, hy + skinScale, 0xFFFFFFFF);
            g.fill(hx, hy, hx + 1, hy + skinScale, 0xFFFFFFFF); g.fill(hx + skinScale - 1, hy, hx + skinScale, hy + skinScale, 0xFFFFFFFF);
            text(g, px + ", " + py, cx + 8, bottom - 14, muted());
        }
        if (skinLoading) text(g, "Loading...", cx + lw - 8 - font.width("Loading..."), bottom - 14, AMBER);

        card(g, rx, top, rw, bottom - top);
        bold(g, "Palette", rx + 10, top + 8, TEXT);
        int sw = Math.max(8, Math.min(16, (rw - 20) / 8));
        for (int i = 0; i < SKIN_PAL.length; i++) {
            int sx = rx + 10 + (i % 8) * (sw + 1), sy = top + 24 + (i / 8) * (sw + 1);
            if (SKIN_PAL[i] == skinColor) g.fill(sx - 1, sy - 1, sx + sw + 1, sy + sw + 1, 0xFFFFFFFF);
            g.fill(sx, sy, sx + sw, sy + sw, SKIN_PAL[i]);
        }
        if (skinHexBox != null) field(g, skinHexBox, rx + 10, top + 74, rw - 20, "Hex colour", mx, my);
        g.fill(rx + rw - 28, top + 62, rx + rw - 10, top + 72, skinColor | 0xFF000000);
        int cwid = (rw - 20 - 8) / 3;
        chip(g, "sktool0", new int[]{rx + 10, top + 98, cwid, 16}, "Pencil", skinTool == 0 ? 1 : 0, cAccent);
        chip(g, "sktool1", new int[]{rx + 14 + cwid, top + 98, cwid, 16}, "Eraser", skinTool == 1 ? 1 : 0, cAccent);
        chip(g, "sktool2", new int[]{rx + 18 + 2 * cwid, top + 98, cwid, 16}, "Picker", skinTool == 2 ? 1 : 0, cAccent);
        chip(g, "skload", new int[]{rx + 10, top + 118, cwid, 16}, "Load mine", 0, cAccent2);
        chip(g, "skundo", new int[]{rx + 14 + cwid, top + 118, cwid, 16}, "Undo", 0, cAccent2);
        chip(g, "skclear", new int[]{rx + 18 + 2 * cwid, top + 118, cwid, 16}, "Clear", 0, RED);
        text(g, fit("Right click erases. Hold to draw", rw - 20), rx + 10, top + 140, muted());
        int fy = top + 154;
        if (fy + 32 <= bottom - 4) {
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int base = skinImg.getRGB(8 + x, 8 + y), hat = skinImg.getRGB(40 + x, 8 + y);
                int c = (hat >>> 24) > 0 ? hat : base;
                if ((c >>> 24) == 0) continue;
                g.fill(rx + 10 + x * 4, fy + y * 4, rx + 10 + x * 4 + 4, fy + y * 4 + 4, c | 0xFF000000);
            }
            text(g, "Face preview", rx + 50, fy + 12, muted());
        }
    }

    // ---- waypoints tab ----
    private static int wpPage = 0;
    private List<String[]> wps = new ArrayList<>();

    private int wpRows() { return Math.max(1, (bottom - top - 28) / 22); }

    private static void saveWaypoints(List<String[]> l) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String[] w : l) lines.add(String.join("\t", Extras.norm(w)));
        Files.write(waypointPath(), lines);
        Extras.wpAt = 0;
    }

    private void saveWps(String ok) {
        try { saveWaypoints(wps); if (ok != null) status = Component.literal(ok); }
        catch (Exception e) { status = Component.literal("Could not save: " + e.getMessage()); }
    }

    private void initWaypoints() {
        wps = loadWaypoints();
        int rows = wpRows(), pages = Math.max(1, (wps.size() + rows - 1) / rows);
        wpPage = clampi(wpPage, 0, pages - 1);
        wpNameBox = makeBox(cx + 6, top - 1, 110, wpName, s -> wpName = s, false);
        wpNameBox.setMaxLength(32);
        wpNameBox.setHint(Component.literal("Name (optional)").withStyle(ChatFormatting.DARK_GRAY));
        hit("wpmark", cx + 120, top, 70, 16, this::markWaypoint);
        hit("wphud", cx + 194, top, 54, 16, () -> { Extras.wpHud = !Extras.wpHud; savePrefs(); });
        hit("wpdeath", cx + 252, top, 62, 16, () -> { Extras.wpDeath = !Extras.wpDeath; savePrefs(); });
        hit("wpprev", cx + cw - 44, top, 18, 16, () -> { wpPage = (wpPage + pages - 1) % pages; init(); });
        hit("wpnext", cx + cw - 24, top, 18, 16, () -> { wpPage = (wpPage + 1) % pages; init(); });
        for (int i = 0; i < rows; i++) {
            final int idx = wpPage * rows + i;
            if (idx >= wps.size()) break;
            int ry = top + 22 + i * 22;
            hit("wpcol" + i, cx + 10, ry + 2, 18, 16, () -> cycleWpColor(idx));
            hit("wpvis" + i, cx + cw - 76, ry, 22, 20, () -> toggleWp(idx));
            hit("wpcopy" + i, cx + cw - 52, ry, 22, 20, () -> copyWaypoint(idx));
            hit("wpdel" + i, cx + cw - 28, ry, 22, 20, () -> deleteWaypoint(idx));
        }
    }

    private void markWaypoint() {
        status = Component.literal(markHere(wpName).replace("*", ""));
        wpName = "";
        init();
    }

    private void cycleWpColor(int idx) {
        if (idx < 0 || idx >= wps.size()) return;
        String[] w = Extras.norm(wps.get(idx));
        int c = 0;
        try { c = Integer.parseInt(w[5].trim()); } catch (Exception ignored) { }
        w[5] = "" + ((Math.abs(c) + 1) % Extras.WP_COL.length);
        wps.set(idx, w);
        saveWps(null);
    }

    private void toggleWp(int idx) {
        if (idx < 0 || idx >= wps.size()) return;
        String[] w = Extras.norm(wps.get(idx));
        w[6] = Extras.visible(w) ? "0" : "1";
        wps.set(idx, w);
        saveWps(w[0] + (w[6].equals("1") ? " shown on screen" : " hidden"));
    }

    private void copyWaypoint(int idx) {
        if (idx < 0 || idx >= wps.size()) return;
        String[] w = wps.get(idx);
        Minecraft.getInstance().keyboardHandler.setClipboard(w[1] + " " + w[2] + " " + w[3]);
        status = Component.literal("Copied " + w[0] + ": " + w[1] + " " + w[2] + " " + w[3]);
    }

    private void deleteWaypoint(int idx) {
        if (idx < 0 || idx >= wps.size()) return;
        wps.remove(idx);
        saveWps("Removed waypoint");
        init();
    }

    private void drawWaypoints(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (wpNameBox != null) field(g, wpNameBox, cx + 6, top - 1, 110, "", mx, my);
        chip(g, "wpmark", new int[]{cx + 120, top, 70, 16}, "Mark here", 0, cAccent);
        chip(g, "wphud", new int[]{cx + 194, top, 54, 16}, Extras.wpHud ? "HUD on" : "HUD off", Extras.wpHud ? 1 : 0, cAccent2);
        chip(g, "wpdeath", new int[]{cx + 252, top, 62, 16}, "Death pt", Extras.wpDeath ? 1 : 0, cAccent2);
        int rows = wpRows(), pages = Math.max(1, (wps.size() + rows - 1) / rows);
        chip(g, "wpprev", new int[]{cx + cw - 44, top, 18, 16}, "<", 0, cAccent);
        chip(g, "wpnext", new int[]{cx + cw - 24, top, 18, 16}, ">", 0, cAccent);
        String pg = wps.size() + " saved  " + (wpPage + 1) + "/" + pages;
        text(g, pg, cx + cw - 50 - font.width(pg), top + 4, muted());
        card(g, cx, top + 20, cw, bottom - top - 20);
        if (wps.isEmpty()) {
            text(g, fit("No waypoints yet. Press Mark here in a world (or !mark <name> in Discord).", cw - 24), cx + 12, top + 32, muted());
            return;
        }
        String curDim = mc.level != null ? Extras.dimension(mc) : "";
        for (int i = 0; i < rows; i++) {
            int idx = wpPage * rows + i;
            if (idx >= wps.size()) break;
            String[] w = Extras.norm(wps.get(idx));
            int ry = top + 22 + i * 22;
            round(g, cx + 6, ry, cw - 12, 20, alpha(cAccent, 0.06f));
            round(g, cx + 12, ry + 4, 14, 12, Extras.color(w));
            boolean vis = Extras.visible(w);
            bold(g, fit(w[0], cw - 270), cx + 32, ry + 6, vis ? TEXT : muted());
            String dist = "";
            if (mc.player != null && w[4].equals(curDim)) {
                try {
                    double dx = Double.parseDouble(w[1]) - mc.player.getX(), dz = Double.parseDouble(w[3]) - mc.player.getZ();
                    dist = "  " + Math.round(Math.sqrt(dx * dx + dz * dz)) + "m";
                } catch (Exception ignored) { }
            }
            String c = w[1] + " " + w[2] + " " + w[3] + "  " + w[4].replace("minecraft:", "") + dist;
            text(g, fit(c, 220), cx + cw - 84 - Math.min(220, font.width(c)), ry + 6, muted());
            round(g, cx + cw - 76, ry, 22, 20, hov("wpvis" + i) ? GREEN : alpha(vis ? GREEN : 0xFF888888, 0.35f));
            text(g, vis ? "on" : "off", cx + cw - 76 + (22 - font.width(vis ? "on" : "off")) / 2, ry + 6, TEXT);
            round(g, cx + cw - 52, ry, 22, 20, hov("wpcopy" + i) ? cAccent : alpha(cAccent, 0.3f));
            text(g, "c", cx + cw - 52 + 8, ry + 6, TEXT);
            round(g, cx + cw - 28, ry, 22, 20, hov("wpdel" + i) ? RED : alpha(RED, 0.25f));
            text(g, "x", cx + cw - 28 + 8, ry + 6, TEXT);
        }
    }

    // ---- training tab: CPS test + aim trainer (everything is a real button) ----
    private static final String[] TRAIN_PAGES = {"CPS", "Aim"};
    private static int trainPage = 0;
    private static final ArrayDeque<Long> cpsClicks = new ArrayDeque<>();
    private static double cpsBest = 0;
    private static final int AIM_R = 10;
    private static final Random AIM_RND = new Random();
    private static boolean aimRunning = false;
    private static long aimEnd = 0, aimSpawn = 0, aimReact = 0;
    private static int aimX = 0, aimY = 0, aimHits = 0, aimMisses = 0, aimBest = 0;
    private Button aimBtn;

    /** Click box in base (unshifted) coordinates. */
    private int[] trainArea() {
        int aw = Math.max(120, cw - 150), ah = Math.max(80, bottom - top - 36);
        return new int[]{baseCx + 8, top + 26, aw, ah};
    }

    private void placeAim() {
        if (aimBtn == null) return;
        aimBtn.setX(aimX - AIM_R);
        aimBtn.setY(aimY - AIM_R);
        aimBtn.visible = aimRunning && trainPage == 1;
    }

    private void aimNew() {
        int[] a = trainArea();
        aimX = a[0] + AIM_R + 2 + AIM_RND.nextInt(Math.max(1, a[2] - 2 * AIM_R - 4));
        aimY = a[1] + AIM_R + 2 + AIM_RND.nextInt(Math.max(1, a[3] - 2 * AIM_R - 4));
        aimSpawn = System.currentTimeMillis();
        placeAim();
    }

    private void trainAction() {
        if (trainPage == 0) { cpsClicks.clear(); cpsBest = 0; return; }
        if (aimRunning) { aimRunning = false; placeAim(); return; }
        aimRunning = true; aimHits = 0; aimMisses = 0; aimReact = 0;
        aimEnd = System.currentTimeMillis() + 30000L;
        aimNew();
    }

    private void initTrain() {
        int chipX = cx + 8;
        for (int i = 0; i < TRAIN_PAGES.length; i++) {
            final int idx = i;
            int w = font.width(TRAIN_PAGES[i]) + 14;
            hit("trchip" + i, chipX, top, w, 16, () -> { trainPage = idx; aimRunning = false; init(); });
            chipX += w + 4;
        }
        int[] a = trainArea();
        // the target is added before the big box so it wins the click
        aimBtn = hit("traim", aimX - AIM_R, aimY - AIM_R, 2 * AIM_R, 2 * AIM_R, () -> {
            if (!aimRunning) return;
            aimHits++;
            aimReact += System.currentTimeMillis() - aimSpawn;
            aimNew();
        });
        aimBtn.visible = aimRunning && trainPage == 1;
        hit("trarea", a[0], a[1], a[2], a[3], () -> {
            if (trainPage == 0) cpsClicks.addLast(System.currentTimeMillis());
            else if (aimRunning) aimMisses++;
        });
        hit("tract", a[0] + a[2] + 12, top + 26 + 90, 126, 18, this::trainAction);
    }

    private void drawTrain(GuiGraphics g) {
        int dx = cx - baseCx;
        int chipX = cx + 8;
        for (int i = 0; i < TRAIN_PAGES.length; i++) {
            int w = font.width(TRAIN_PAGES[i]) + 14;
            float sel = ease("trchip" + i, trainPage == i ? 1 : 0, 14);
            round(g, chipX, top, w, 16, mix(cCard, cAccent, 0.15f + 0.8f * sel));
            text(g, TRAIN_PAGES[i], chipX + 7, top + 4, mix(TEXT, cBg, sel));
            chipX += w + 4;
        }
        int[] a = trainArea();
        a[0] += dx;
        int px2 = a[0] + a[2] + 12, pw2 = 126;
        float hv = ease("trareahov", hov("trarea") ? 1 : 0, 14);
        g.fill(a[0] - 1, a[1] - 1, a[0] + a[2] + 1, a[1] + a[3] + 1, cLine);
        g.fill(a[0], a[1], a[0] + a[2], a[1] + a[3], mix(mix(cCard, cBg, 0.35f), cAccent, 0.10f * hv));
        card(g, px2 - 4, a[1], pw2 + 8, a[3]);
        long now = System.currentTimeMillis();
        if (trainPage == 0) {
            while (!cpsClicks.isEmpty() && now - cpsClicks.peekFirst() > 5000) cpsClicks.removeFirst();
            int n1 = 0;
            for (long t : cpsClicks) if (now - t <= 1000) n1++;
            cpsBest = Math.max(cpsBest, n1);
            String big = "" + n1;
            text(g, "Click in this box as fast as you can", a[0] + (a[2] - font.width("Click in this box as fast as you can")) / 2, a[1] + a[3] / 2 - 20, muted());
            bold(g, big, a[0] + (a[2] - font.width(big)) / 2, a[1] + a[3] / 2 - 4, TEXT);
            text(g, "CPS", a[0] + (a[2] - font.width("CPS")) / 2, a[1] + a[3] / 2 + 8, muted());
            bold(g, "Stats", px2, a[1] + 8, TEXT);
            text(g, "Now: " + n1, px2, a[1] + 24, TEXT);
            text(g, String.format(Locale.ROOT, "Average (5s): %.1f", cpsClicks.size() / 5.0), px2, a[1] + 38, TEXT);
            text(g, "Best: " + (int) cpsBest, px2, a[1] + 52, TEXT);
            chip(g, "tract", new int[]{px2, a[1] + 90, pw2, 18}, "Reset", 0, cAccent);
        } else {
            if (aimRunning) {
                if (now >= aimEnd) { aimRunning = false; aimBest = Math.max(aimBest, aimHits); placeAim(); }
                else if (now - aimSpawn > 1500) { aimMisses++; aimNew(); }
            }
            if (aimRunning) {
                float ah = ease("traimhov", hov("traim") ? 1 : 0, 18);
                disc(g, aimX + dx, aimY, AIM_R, mix(cAccent, 0xFFFFFFFF, 0.3f * ah));
                disc(g, aimX + dx, aimY, AIM_R / 2, 0xFFFFFFFF);
            } else {
                String t = aimHits + aimMisses > 0 ? "Round over: " + aimHits + " hits" : "Press Start, then click the targets";
                text(g, t, a[0] + (a[2] - font.width(t)) / 2, a[1] + a[3] / 2 - 4, muted());
            }
            int total = aimHits + aimMisses;
            bold(g, aimRunning ? "Time " + Math.max(0, (aimEnd - now) / 1000) + "s" : "30 second round", px2, a[1] + 8, TEXT);
            text(g, "Hits: " + aimHits, px2, a[1] + 24, TEXT);
            text(g, "Misses: " + aimMisses, px2, a[1] + 36, TEXT);
            text(g, "Accuracy: " + (total == 0 ? "-" : (100 * aimHits / total) + "%"), px2, a[1] + 48, TEXT);
            text(g, "Avg reaction: " + (aimHits == 0 ? "-" : (aimReact / aimHits) + " ms"), px2, a[1] + 60, TEXT);
            text(g, "Best round: " + aimBest, px2, a[1] + 72, muted());
            chip(g, "tract", new int[]{px2, a[1] + 90, pw2, 18}, aimRunning ? "Stop" : "Start", 0, cAccent);
        }
    }

    // ---- players tab ----
    private static int playerPage = 0;

    private List<PlayerInfo> onlinePlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return new ArrayList<>();
        List<PlayerInfo> l = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        l.sort(Comparator.comparing(p -> p.getProfile().name().toLowerCase(Locale.ROOT)));
        return l;
    }

    private int playerRows() { return Math.max(1, (bottom - top - 30) / 18); }

    private void initPlayers() {
        int pages = Math.max(1, (onlinePlayers().size() + playerRows() - 1) / playerRows());
        playerPage = clampi(playerPage, 0, pages - 1);
        hit("pprev", cx + cw - 46, top + 4, 18, 16, () -> { playerPage = (playerPage + pages - 1) % pages; });
        hit("pnext", cx + cw - 24, top + 4, 18, 16, () -> { playerPage = (playerPage + 1) % pages; });
    }

    private void drawPlayers(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        card(g, cx, top, cw, bottom - top);
        List<PlayerInfo> all = onlinePlayers();
        int rows = playerRows(), pages = Math.max(1, (all.size() + rows - 1) / rows);
        playerPage = clampi(playerPage, 0, pages - 1);
        bold(g, "Players online (" + all.size() + ")", cx + 10, top + 8, TEXT);
        String pg = (playerPage + 1) + "/" + pages;
        text(g, pg, cx + cw - 50 - font.width(pg), top + 8, muted());
        chip(g, "pprev", new int[]{cx + cw - 46, top + 4, 18, 16}, "<", 0, cAccent);
        chip(g, "pnext", new int[]{cx + cw - 24, top + 4, 18, 16}, ">", 0, cAccent);
        if (all.isEmpty()) { text(g, "Join a world or server to see players", cx + 12, top + 28, muted()); return; }
        int nameW = Math.max(60, cw / 3);
        for (int i = 0; i < rows; i++) {
            int idx = playerPage * rows + i;
            if (idx >= all.size()) break;
            PlayerInfo info = all.get(idx);
            int ry = top + 26 + i * 18;
            try { PlayerFaceRenderer.draw(g, info.getSkin(), cx + 10, ry + 1, 12); } catch (Exception ignored) { }
            text(g, fit(info.getProfile().name(), nameW - 16), cx + 26, ry + 5, TEXT);
            int lat = info.getLatency();
            String ping = lat > 0 ? lat + " ms" : "-";
            text(g, ping, cx + cw - 12 - font.width(ping), ry + 5, lat > 0 && lat < 100 ? GREEN : lat < 250 ? AMBER : RED);
            net.minecraft.world.entity.player.Player pl = mc.level != null ? mc.level.getPlayerByUUID(info.getProfile().id()) : null;
            int bx = cx + 26 + nameW, bw = Math.max(40, cw - nameW - 26 - 12 - 70);
            if (pl != null) {
                float hp = pl.getHealth(), max = Math.max(1f, pl.getMaxHealth());
                g.fill(bx, ry + 4, bx + bw, ry + 12, mix(cCard, cBg, 0.6f));
                g.fill(bx, ry + 4, bx + Math.round(bw * Math.min(1f, hp / max)), ry + 12, hp / max > 0.5f ? GREEN : hp / max > 0.25f ? AMBER : RED);
                String t = Math.round(hp) + "/" + Math.round(max);
                text(g, t, bx + (bw - font.width(t)) / 2, ry + 4, 0xFFFFFFFF);
            } else {
                text(g, "out of range", bx, ry + 5, muted());
            }
        }
    }

    // ---- stats tab ----
    private void copyPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { status = Component.literal("Join a world first"); return; }
        String p = Math.round(mc.player.getX()) + " " + Math.round(mc.player.getY()) + " " + Math.round(mc.player.getZ());
        mc.keyboardHandler.setClipboard(p);
        status = Component.literal("Copied position: " + p);
    }

    private void drawStats(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        card(g, cx, top, cw, bottom - top);
        bold(g, "Live stats", cx + 10, top + 8, TEXT);
        if (mc.player == null) { text(g, "Join a world to see your stats", cx + 12, top + 26, muted()); return; }
        var p = mc.player;
        int lat = 0, online = 0;
        if (mc.getConnection() != null) {
            online = mc.getConnection().getOnlinePlayers().size();
            PlayerInfo me = mc.getConnection().getPlayerInfo(p.getUUID());
            if (me != null) lat = me.getLatency();
        }
        String[][] rows = {
                {"Position", Math.round(p.getX()) + "  " + Math.round(p.getY()) + "  " + Math.round(p.getZ())},
                {"Health", Math.round(p.getHealth()) + " / " + Math.round(p.getMaxHealth())},
                {"Food", p.getFoodData().getFoodLevel() + " / 20"},
                {"Armor", "" + p.getArmorValue()},
                {"Ping", lat > 0 ? lat + " ms" : "-"},
                {"Players online", "" + online},
        };
        for (int i = 0; i < rows.length; i++) {
            int ry = top + 28 + i * 16;
            round(g, cx + 8, ry - 2, cw - 16, 14, alpha(cAccent, 0.06f));
            text(g, rows[i][0], cx + 14, ry + 1, muted());
            text(g, rows[i][1], cx + cw - 14 - font.width(rows[i][1]), ry + 1, TEXT);
        }
    }

    // ---- chat tab layout ----
    private void initChat() {
        int lw = discLeftW(), fx0 = cx + 12, fw = lw - 24;
        chatChanBox = makeBox(fx0, top + 34, fw, DiscordBot.chatChannelId, s -> DiscordBot.chatChannelId = s.trim(), false);
        hit("opt12", cx + 6, top + 60, lw - 12, 16, () -> DiscordBot.logChat = !DiscordBot.logChat);
        hit("opt13", cx + 6, top + 78, lw - 12, 16, () -> DiscordBot.sayOn = !DiscordBot.sayOn);
        hit("opt14", cx + 6, top + 96, lw - 12, 16, () -> DiscordBot.hourly = !DiscordBot.hourly);
        hit("opt15", cx + 6, top + 114, lw - 12, 16, () -> DiscordBot.chatEmbeds = !DiscordBot.chatEmbeds);
        int bw = (fw - 4) / 2;
        hit("clog", fx0, top + 142, bw, 18, () -> { ChatBridge.sendLogNow(); status = Component.literal("Sending the chat log..."); });
        hit("ctest", fx0 + bw + 4, top + 142, bw, 18, () -> DiscordBot.sendChatText("Test message from Trim Studio"));
    }

    private void sendChat() {
        String t = chatText.trim();
        if (t.isEmpty()) return;
        DiscordBot.sendText(t);
        status = Component.literal("Sending to Discord...");
        chatText = "";
        if (chatBox != null) { building = true; chatBox.setValue(""); building = false; }
    }

    // ---- discord scripts page ----
    private void initScripts() {
        int lw = discLeftW(), fx0 = cx + 12, fw = lw - 24;
        hit("opt16", cx + 6, top + 24, lw - 12, 16, () -> DiscordBot.scriptsOn = !DiscordBot.scriptsOn);
        hit("opt17", cx + 6, top + 42, lw - 12, 16, () -> { DiscordBot.lockToken = !DiscordBot.lockToken; savePrefs(); });
        hit("opt18", cx + 6, top + 60, lw - 12, 16, () -> DiscordBot.autoConnect = !DiscordBot.autoConnect);
        scriptBox = makeBox(fx0, top + 98, fw, scriptText, s -> scriptText = s, false);
        scriptBox.setMaxLength(400);
        scriptNameBox = makeBox(fx0, top + 136, fw, scriptName, s -> scriptName = s, false);
        int bw = (fw - 8) / 3, by = top + 162;
        hit("srun", fx0, by, bw, 18, () -> status = Component.literal(Scripts.run(scriptText).replace("`", "")));
        hit("sstop", fx0 + bw + 4, by, bw, 18, () -> status = Component.literal(Scripts.stop()));
        hit("ssave", fx0 + 2 * (bw + 4), by, bw, 18, () -> { status = Component.literal(Scripts.save(scriptName, scriptText).replace("`", "")); init(); });
        int rx = cx + lw + 8, rw = cw - lw - 8;
        List<String> names = Scripts.names();
        int n = Math.min(names.size(), Math.max(1, (bottom - top - 100) / 12));
        for (int i = 0; i < n; i++) {
            final String nm = names.get(i);
            hit("srow" + i, rx + 4, top + 22 + i * 12, rw - 8, 12, () -> {
                scriptName = nm;
                scriptText = Scripts.get(nm) == null ? "" : Scripts.get(nm);
                init();
            });
        }
    }

    private void drawScripts(GuiGraphics g, int mx, int my) {
        int lw = discLeftW(), fx0 = cx + 12, fw = lw - 24;
        card(g, cx, top, lw, bottom - top);
        bold(g, "Scripts from Discord", cx + 10, top + 8, TEXT);
        optionRow(g, 16, cx + 6, top + 24, lw - 12, "Allow scripts", null, DiscordBot.scriptsOn);
        optionRow(g, 17, cx + 6, top + 42, lw - 12, "Lock bot token", null, DiscordBot.lockToken);
        optionRow(g, 18, cx + 6, top + 60, lw - 12, "Auto connect on join", null, DiscordBot.autoConnect);
        if (scriptBox != null) field(g, scriptBox, fx0, top + 98, fw, "Steps (separate with ;)", mx, my);
        if (scriptNameBox != null) field(g, scriptNameBox, fx0, top + 136, fw, "Name (to save)", mx, my);
        int bw = (fw - 8) / 3, by = top + 162;
        chip(g, "srun", new int[]{fx0, by, bw, 18}, "Run", 0, cAccent);
        chip(g, "sstop", new int[]{fx0 + bw + 4, by, bw, 18}, "Stop", 0, RED);
        chip(g, "ssave", new int[]{fx0 + 2 * (bw + 4), by, bw, 18}, "Save", 0, cAccent2);
        boolean idSet = !DiscordBot.userId.isBlank();
        text(g, fit(idSet ? "Obeys Discord user " + DiscordBot.userId : "Set your user ID on the Bot page first", lw - 20), cx + 10, top + 186,
                idSet ? GREEN : AMBER);
        text(g, fit(Scripts.isActive() ? "A script is running" : "No script running", lw - 20), cx + 10, top + 198, Scripts.isActive() ? GREEN : muted());

        int rx = cx + lw + 8, rw = cw - lw - 8, rh = bottom - top;
        card(g, rx, top, rw, rh);
        bold(g, "Saved scripts (click to edit)", rx + 10, top + 8, TEXT);
        List<String> names = Scripts.names();
        int n = Math.min(names.size(), Math.max(1, (rh - 100) / 12));
        if (names.isEmpty()) text(g, "Nothing saved yet", rx + 10, top + 24, muted());
        for (int i = 0; i < n; i++) {
            float hv = ease("srowhov" + i, hov("srow" + i) ? 1 : 0, 14);
            if (hv > 0.02f) round(g, rx + 4, top + 22 + i * 12, rw - 8, 12, alpha(cAccent, 0.12f * hv));
            String t = names.get(i) + ": " + Scripts.get(names.get(i));
            text(g, fit(t, rw - 20), rx + 10, top + 24 + i * 12, TEXT);
        }
        int hy = top + rh - 66;
        text(g, "Discord: !run walk 3; jump", rx + 10, hy, muted());
        text(g, "!script <name>  !scripts  !stop", rx + 10, hy + 11, muted());
        text(g, fit("walk run back left right sneak use <sec>", rw - 20), rx + 10, hy + 22, muted());
        text(g, fit("jump attack <n>  wait <sec>  look <yaw> <pitch>", rw - 20), rx + 10, hy + 33, muted());
        text(g, fit("turn <deg>  repeat <n> ... end  stop", rw - 20), rx + 10, hy + 44, muted());
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
            case T_WAYPOINTS -> "Mark here";
            case T_DISCORD -> discPage == 2 ? (Scripts.isActive() ? "Stop script" : "Run script") : discPage == 1 ? "Send chat log now" : (DiscordBot.isRunning() ? "Disconnect bot" : "Connect bot");
            case T_COMBO -> "Reset combo";
            case T_SKIN -> "Save skin PNG";
            case T_PLAYERS -> "Refresh list";
            case T_STATS -> "Copy my position";
            case T_VOICE -> "Refresh list";
            case T_TRAIN -> trainPage == 0 ? "Reset CPS" : (aimRunning ? "Stop" : "Start aim round");
            default -> "Reset preview";
        };
    }

    private void doApply() {
        switch (tab) {
            case T_TRIMS -> copyGive();
            case T_WARDROBE -> saveLoadout();
            case T_WAYPOINTS -> markWaypoint();
            case T_DISCORD -> {
                if (discPage == 2) { status = Component.literal(Scripts.isActive() ? Scripts.stop() : Scripts.run(scriptText).replace("`", "")); }
                else if (discPage == 1) { ChatBridge.sendLogNow(); status = Component.literal("Sending the chat log..."); }
                else if (DiscordBot.isRunning()) DiscordBot.stop(); else { savePrefs(); DiscordBot.start(); }
            }
            case T_COMBO -> { Extras.resetCombo(); status = Component.literal("Combo stats reset"); }
            case T_SKIN -> saveSkin();
            case T_PLAYERS -> init();
            case T_STATS -> copyPos();
            case T_VOICE -> init();
            case T_TRAIN -> trainAction();
            default -> { viewYaw = 0.35f; status = Component.literal("Preview reset"); }
        }
    }

    private void copyGive() {
        Minecraft.getInstance().keyboardHandler.setClipboard(giveCommand());
        status = Component.literal("Copied: " + ARMOR_NAMES[armorType] + " chestplate, " + PATTERNS[pattern] + " + " + MATS[material]);
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
        if (chatBox != null && chatBox.isFocused() && (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER)) { sendChat(); return true; }
        if (anyTyping()) {
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                if (search != null) search.setFocused(false);
                for (EditBox b : fields) b.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(e);
        }
        if (k >= GLFW.GLFW_KEY_1 && k < GLFW.GLFW_KEY_1 + TABS.length) { setTab(k - GLFW.GLFW_KEY_1); return true; }
        if (k == GLFW.GLFW_KEY_L) { setLayout(layout + 1); return true; }
        if (k == GLFW.GLFW_KEY_TAB && layout == L_RADIAL) { radialOpen = !radialOpen; init(); return true; }
        return super.keyPressed(e);
    }

    /** Polled each frame: sliders, colour picking and preview rotation (no mouse event overrides needed). */
    private void pollMouse(int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        int prevMx = lastMx;
        lastMx = mx;
        boolean left = mc.mouseHandler.isLeftPressed(), right = mc.mouseHandler.isRightPressed();
        boolean pressed = left || right, edge = pressed && !wasPressed;
        wasPressed = pressed;
        if (!pressed) { sliderDrag = -1; prevDrag = false; return; }
        if (radialOpen) return;

        if (tab == T_TRIMS) {
            if (edge && mx >= prevBox[0] && mx < prevBox[2] && my >= prevBox[1] && my < prevBox[3]) prevDrag = true;
            if (prevDrag) viewYaw = Math.max(-1.2f, Math.min(1.2f, viewYaw + (mx - prevMx) / 45f));
        } else if (tab == T_SKIN) {
            skinPaint(mx, my, left, right, edge);
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
    private PlayerSkin skin() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getSkin() : DefaultPlayerSkin.get(mc.getUser().getProfileId());
    }

    /** Skin of a chat sender: the real one while they are in the tab list, otherwise the default skin for their UUID. */
    private static PlayerSkin skinFor(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) return info.getSkin();
        }
        return DefaultPlayerSkin.get(id);
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
            case T_WAYPOINTS -> { // map pin
                disc(g, x + 7, y + 5, 5, c); disc(g, x + 7, y + 5, 2, bgc); g.fill(x + 6, y + 9, x + 8, y + 14, c);
            }
            case T_DISCORD -> { // game-pad-ish chat face
                g.fill(x + 1, y + 2, x + 13, y + 12, c); g.fill(x + 3, y + 12, x + 6, y + 14, c); g.fill(x + 8, y + 12, x + 11, y + 14, c);
                g.fill(x + 3, y + 5, x + 5, y + 8, bgc); g.fill(x + 9, y + 5, x + 11, y + 8, bgc);
            }
            case T_COMBO -> { // flame
                g.fill(x + 5, y, x + 9, y + 3, c); g.fill(x + 3, y + 3, x + 11, y + 8, c); g.fill(x + 1, y + 8, x + 13, y + 14, c); g.fill(x + 5, y + 9, x + 9, y + 13, bgc);
            }
            case T_SKIN -> { // brush
                g.fill(x + 8, y, x + 13, y + 5, c); g.fill(x + 5, y + 4, x + 11, y + 9, c); g.fill(x + 1, y + 8, x + 7, y + 14, c); g.fill(x + 3, y + 10, x + 5, y + 12, bgc);
            }
            case T_PLAYERS -> { // two heads
                g.fill(x + 1, y + 1, x + 6, y + 6, c); g.fill(x, y + 8, x + 7, y + 14, c);
                g.fill(x + 8, y + 3, x + 13, y + 8, c); g.fill(x + 7, y + 9, x + 14, y + 14, c);
            }
            case T_VOICE -> { // microphone
                g.fill(x + 5, y, x + 9, y + 8, c); g.fill(x + 3, y + 6, x + 4, y + 9, c); g.fill(x + 10, y + 6, x + 11, y + 9, c);
                g.fill(x + 3, y + 9, x + 11, y + 10, c); g.fill(x + 6, y + 10, x + 8, y + 13, c); g.fill(x + 4, y + 13, x + 10, y + 14, c);
            }
            case T_TRAIN -> { // lightning bolt
                g.fill(x + 7, y, x + 11, y + 6, c); g.fill(x + 3, y + 6, x + 11, y + 8, c); g.fill(x + 3, y + 8, x + 7, y + 14, c);
            }
            case T_STATS -> { // bars
                g.fill(x + 1, y + 8, x + 4, y + 14, c); g.fill(x + 5, y + 4, x + 8, y + 14, c); g.fill(x + 9, y + 1, x + 12, y + 14, c);
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
                case T_WAYPOINTS -> drawWaypoints(g, mx, my);
                case T_DISCORD -> drawDiscordTab(g, mx, my);
                case T_COMBO -> drawCombo(g, mx, my);
                case T_SKIN -> drawSkin(g, mx, my);
                case T_PLAYERS -> drawPlayers(g, mx, my);
                case T_STATS -> drawStats(g);
                case T_VOICE -> drawVoice(g);
                case T_TRAIN -> drawTrain(g);
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
        if (msg.isEmpty()) msg = "1-9 tabs  |  L layout  |  Ctrl+K search";
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
        if (tokenBox != null) field(g, tokenBox, fx0, top + 43, fw, DiscordBot.lockToken ? "Bot token (locked)" : "Bot token (kept on this PC)", mx, my);
        if (chanBox != null) field(g, chanBox, fx0, top + 73, fw, "Channel ID", mx, my);
        if (userBox != null) field(g, userBox, fx0, top + 103, fw, "Only obey this user ID (optional)", mx, my);
        optionRow(g, 5, cx + 6, top + 123, lw - 12, "Listen for !commands", null, DiscordBot.listen);
        int bw = (lw - 24 - 4) / 2, by = top + 163;
        chip(g, "dcon", new int[]{fx0, by, bw, 18}, on ? "Disconnect" : "Connect", on ? 1 : 0, RED);
        chip(g, "dtrim", new int[]{fx0 + bw + 4, by, bw, 18}, "Send trim", 0, cAccent);
        optionRow(g, 11, cx + 6, top + 185, lw - 12, "Show Discord chat in game", null, DiscordBot.relay);
        if (chatBox != null) field(g, chatBox, fx0, top + 221, fw - 52, "Message to Discord (Enter sends)", mx, my);
        chip(g, "dchat", new int[]{fx0 + fw - 48, top + 221, 48, 18}, "Send", 0, cAccent);

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
        text(g, fit("!trim  !random  !players  !wp", rw - 20), rx + 10, hy + 11, TEXT);
        text(g, fit("!set <armor> <pattern> <material>", rw - 20), rx + 10, hy + 22, TEXT);
        text(g, fit("Enable Message Content Intent on the bot", rw - 20), rx + 10, hy + 33, muted());
    }

    // ---- chat tab ----
    private void drawChat(GuiGraphics g, int mx, int my) {
        int lw = discLeftW(), fx0 = cx + 12, fw = lw - 24;
        card(g, cx, top, lw, bottom - top);
        bold(g, "Minecraft chat bridge", cx + 10, top + 7, TEXT);
        if (chatChanBox != null) field(g, chatChanBox, fx0, top + 34, fw, "Chat channel ID", mx, my);
        optionRow(g, 12, cx + 6, top + 60, lw - 12, "Log all chat to Discord", null, DiscordBot.logChat);
        optionRow(g, 13, cx + 6, top + 78, lw - 12, "Allow !say from Discord", null, DiscordBot.sayOn);
        optionRow(g, 14, cx + 6, top + 96, lw - 12, "Hourly chat file (in game only)", null, DiscordBot.hourly);
        optionRow(g, 15, cx + 6, top + 114, lw - 12, "Show player heads in Discord", null, DiscordBot.chatEmbeds);
        int bw = (fw - 4) / 2;
        chip(g, "clog", new int[]{fx0, top + 142, bw, 18}, "Send log now", 0, cAccent);
        chip(g, "ctest", new int[]{fx0 + bw + 4, top + 142, bw, 18}, "Test message", 0, cAccent);
        boolean on = DiscordBot.isRunning();
        text(g, fit(on ? "Bot is online" : "Connect the bot on the Discord tab first", lw - 20), cx + 10, top + 168, on ? GREEN : muted());

        int rx = cx + lw + 8, rw = cw - lw - 8, rh = bottom - top;
        card(g, rx, top, rw, rh);
        bold(g, "Recent chat", rx + 10, top + 7, TEXT);
        List<ChatBridge.Line> lines = ChatBridge.recent();
        int n = Math.max(1, (rh - 64) / 10);
        int start = Math.max(0, lines.size() - n);
        if (lines.isEmpty()) text(g, "Nothing yet", rx + 10, top + 22, muted());
        for (int i = start; i < lines.size(); i++) {
            ChatBridge.Line l = lines.get(i);
            int ly = top + 22 + (i - start) * 10;
            if (l.id() != null) {
                try { PlayerFaceRenderer.draw(g, skinFor(l.id()), rx + 10, ly, 8); } catch (Exception ignored) { }
            }
            text(g, fit(l.plain(), rw - 31), rx + 21, ly, l.id() != null ? TEXT : muted());
        }
        int hy = top + rh - 34;
        text(g, fit("In the chat channel: !say <message>", rw - 20), rx + 10, hy, muted());
        text(g, fit("says it in game as you (no / commands).", rw - 20), rx + 10, hy + 11, muted());
        text(g, fit("Set a user ID on the Discord tab for safety.", rw - 20), rx + 10, hy + 22, muted());
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
