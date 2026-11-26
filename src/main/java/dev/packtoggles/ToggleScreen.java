package dev.packtoggles;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Dark-brown / orange dashboard look. Everything is drawn by hand in render();
 * the vanilla Buttons are fully transparent and only provide the click areas.
 */
public class ToggleScreen extends Screen {
    // palette (ARGB)
    private static final int BG = 0xFF170D0A, CARD = 0xFF24150F, CARD_HI = 0xFF2F1B13, LINE = 0xFF3A2218;
    private static final int ORANGE = 0xFFF08A24, ORANGE_DIM = 0xFFB35F18, TEXT = 0xFFF5E8DD, MUTED = 0xFF9C8576;
    private static final int GREEN = 0xFF58D26F, RED = 0xFFE0564F, OFF_TRACK = 0xFF4B3A32;

    private static final String[] TAB_NAMES = {"All", "MC", "Mods", "Misc"};
    private static final Set<String> MC_IDS = Set.of("blocks", "items", "gui", "entities", "effects", "particles", "ranks", "misc_tex");
    private static final Set<String> MISC_IDS = Set.of("sounds", "shaders", "optifine", "lang", "respackopts",
            "carl", "potions_icons", "visible_cooldown", "entity_features");

    private final Screen parent;
    private int tab = 0, page = 0;
    private Component status = Component.empty();

    // layout (recomputed in init)
    private int px, py, pw, ph, cx, cw, listY, listH, rows, rowH = 22;
    private final List<Button> tabBtns = new ArrayList<>(), rowBtns = new ArrayList<>();
    private final List<Group> rowGroups = new ArrayList<>();
    private Button sourceBtn, closeBtn, prevBtn, nextBtn, applyBtn;
    private int applyX, applyY, applyW, applyH, srcX, srcY, srcW, srcH;

    public ToggleScreen(Screen parent) {
        super(Component.translatable("screen.packtoggles.title"));
        this.parent = parent;
        if (Config.source.isEmpty()) {
            List<String> c = PackBuilder.candidates();
            if (!c.isEmpty()) Config.source = c.get(0);
        }
    }

    private List<Group> visible() {
        List<Group> out = new ArrayList<>();
        for (Group g : Group.ALL) {
            boolean mc = MC_IDS.contains(g.id()), misc = MISC_IDS.contains(g.id());
            if (tab == 0 || (tab == 1 && mc) || (tab == 2 && !mc && !misc) || (tab == 3 && misc)) out.add(g);
        }
        return out;
    }

    private Button hit(int x, int y, int w, int h, Runnable r) {
        Button b = Button.builder(Component.empty(), btn -> r.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f); // invisible, still clickable
        return addRenderableWidget(b);
    }

    @Override
    protected void init() {
        clearWidgets();
        tabBtns.clear(); rowBtns.clear(); rowGroups.clear();
        pw = Math.min(width - 24, 560);
        ph = Math.min(height - 16, 340);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 52;
        cw = pw - 60;

        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            tabBtns.add(hit(px + 8, py + 44 + i * 34, 34, 28, () -> { tab = idx; page = 0; init(); }));
        }
        closeBtn = hit(px + pw - 26, py + 10, 16, 16, this::onClose);
        srcW = 170; srcH = 18; srcX = px + pw - 26 - 8 - srcW; srcY = py + 9;
        sourceBtn = hit(srcX, srcY, srcW, srcH, this::cycleSource);

        listY = py + 34 + 36 + 8;
        applyH = 24; applyW = 150; applyX = cx + cw - applyW; applyY = py + ph - 8 - applyH;
        listH = applyY - 8 - listY;
        rows = Math.max(1, (listH - 28) / rowH);

        List<Group> vis = visible();
        int pages = Math.max(1, (vis.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < rows; i++) {
            int idx = page * rows + i;
            if (idx >= vis.size()) break;
            Group g = vis.get(idx);
            rowGroups.add(g);
            rowBtns.add(hit(cx + 6, listY + 24 + i * rowH, cw - 12, rowH - 2, () -> {
                if (!Config.disabled.remove(g.id())) Config.disabled.add(g.id());
            }));
        }
        prevBtn = hit(cx + cw - 52, listY + 4, 20, 16, () -> { page = (page + pages - 1) % pages; init(); });
        nextBtn = hit(cx + cw - 26, listY + 4, 20, 16, () -> { page = (page + 1) % pages; init(); });
        applyBtn = hit(applyX, applyY, applyW, applyH, this::apply);
    }

    // ---------- actions ----------
    private void cycleSource() {
        List<String> c = PackBuilder.candidates();
        if (c.isEmpty()) { status = Component.literal("No .zip packs in resourcepacks/"); return; }
        Config.source = c.get((Math.max(0, c.indexOf(Config.source)) + 1) % c.size());
    }

    private void apply() {
        if (Config.source.isEmpty()) { status = Component.literal("Pick a source pack first"); return; }
        Config.save();
        try {
            if (PackBuilder.build()) {
                status = Component.literal("Built " + PackBuilder.outputName() + " - select it once in Resource Packs");
                Minecraft.getInstance().reloadResourcePacks();
            } else {
                status = Component.literal("Pack is in use - restart the game to apply");
            }
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Build failed", e);
            status = Component.literal("Failed: " + e.getMessage());
        }
    }

    // ---------- drawing helpers ----------
    private static void round(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 2, y, x + w - 2, y + h, c);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, c);
        g.fill(x, y + 2, x + w, y + h - 2, c);
    }

    private static int withAlpha(int rgb, int a) { return (a << 24) | (rgb & 0xFFFFFF); }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, false);
    }

    private void bold(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font, Component.literal(s).withStyle(ChatFormatting.BOLD), x, y, color, false);
    }

    private String fit(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "..") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private void card(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x, y, w, h, LINE);
        round(g, x + 1, y + 1, w - 2, h - 2, CARD);
    }

    // ---------- render ----------
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta); // vanilla background + transparent click areas

        // glow border (pulses slowly)
        int pulse = 28 + (int) (14 * Math.sin(System.currentTimeMillis() / 600.0));
        for (int i = 4; i >= 1; i--) round(g, px - i, py - i, pw + 2 * i, ph + 2 * i, withAlpha(0xF08A24, pulse / i));
        round(g, px, py, pw, ph, ORANGE_DIM);
        round(g, px + 1, py + 1, pw - 2, ph - 2, BG);
        g.fill(px + 20, py + 1, px + pw / 2, py + 2, ORANGE); // top accent line

        // ---- sidebar ----
        round(g, px + 6, py + 6, 38, ph - 12, CARD);
        round(g, px + 14, py + 12, 22, 22, ORANGE);           // logo dot
        round(g, px + 19, py + 17, 12, 12, 0xFFFFC27A);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            boolean sel = i == tab, hov = tabBtns.get(i).isHoveredOrFocused();
            int bx = px + 8, by = py + 44 + i * 34;
            if (sel) round(g, bx, by, 34, 28, ORANGE);
            else if (hov) round(g, bx, by, 34, 28, CARD_HI);
            String n = TAB_NAMES[i];
            text(g, n, bx + (34 - font.width(n)) / 2, by + 10, sel ? 0xFF1B0F0B : TEXT);
            if (sel) g.fill(px + 6, by + 6, px + 8, by + 22, ORANGE);
        }

        // ---- header ----
        bold(g, "Overview", cx, py + 12, TEXT);
        round(g, srcX, srcY, srcW, srcH, sourceBtn.isHoveredOrFocused() ? CARD_HI : CARD);
        g.fill(srcX + 8, srcY + 7, srcX + 12, srcY + 11, ORANGE);
        text(g, fit(Config.source.isEmpty() ? "No source pack" : Config.source, srcW - 26), srcX + 18, srcY + 5, MUTED);
        round(g, px + pw - 26, py + 10, 16, 16, closeBtn.isHoveredOrFocused() ? RED : CARD_HI);
        text(g, "x", px + pw - 21, py + 14, TEXT);

        // ---- stat cards ----
        int off = 0;
        for (Group gr : Group.ALL) if (Config.disabled.contains(gr.id())) off++;
        int total = Group.ALL.size(), on = total - off;
        int gap = 8, w3 = (cw - 2 * gap) / 3, cy = py + 34;
        card(g, cx, cy, w3, 36);
        text(g, "Enabled", cx + 8, cy + 6, MUTED);
        bold(g, on + " / " + total, cx + 8, cy + 18, GREEN);
        card(g, cx + w3 + gap, cy, w3, 36);
        text(g, "Disabled", cx + w3 + gap + 8, cy + 6, MUTED);
        bold(g, String.valueOf(off), cx + w3 + gap + 8, cy + 18, off == 0 ? TEXT : RED);
        int c3 = cx + 2 * (w3 + gap);
        card(g, c3, cy, cx + cw - c3, 36);
        text(g, "Kept in pack", c3 + 8, cy + 6, MUTED);
        int barW = cx + cw - c3 - 16;
        g.fill(c3 + 8, cy + 24, c3 + 8 + barW, cy + 28, OFF_TRACK);
        g.fill(c3 + 8, cy + 24, c3 + 8 + (int) (barW * (on / (double) Math.max(1, total))), cy + 28, ORANGE);

        // ---- list card ----
        card(g, cx, listY, cw, listH);
        bold(g, "Features", cx + 8, listY + 7, TEXT);
        List<Group> vis = visible();
        int pages = Math.max(1, (vis.size() + rows - 1) / rows);
        String pg = (page + 1) + "/" + pages;
        text(g, pg, cx + cw - 56 - font.width(pg), listY + 8, MUTED);
        round(g, cx + cw - 52, listY + 4, 20, 16, prevBtn.isHoveredOrFocused() ? ORANGE_DIM : CARD_HI);
        text(g, "<", cx + cw - 45, listY + 8, TEXT);
        round(g, cx + cw - 26, listY + 4, 20, 16, nextBtn.isHoveredOrFocused() ? ORANGE_DIM : CARD_HI);
        text(g, ">", cx + cw - 19, listY + 8, TEXT);
        g.fill(cx + 6, listY + 22, cx + cw - 6, listY + 23, LINE);

        for (int i = 0; i < rowGroups.size(); i++) {
            Group gr = rowGroups.get(i);
            boolean enabled = !Config.disabled.contains(gr.id());
            int ry = listY + 24 + i * rowH;
            if (rowBtns.get(i).isHoveredOrFocused()) round(g, cx + 6, ry, cw - 12, rowH - 2, CARD_HI);
            text(g, fit(gr.label(), cw - 90), cx + 14, ry + 6, enabled ? TEXT : MUTED);
            // switch
            int sx = cx + cw - 14 - 26, sy = ry + 4;
            round(g, sx, sy, 26, 12, enabled ? ORANGE : OFF_TRACK);
            round(g, enabled ? sx + 15 : sx + 1, sy + 1, 10, 10, 0xFFFFFFFF);
        }
        if (rowGroups.isEmpty()) text(g, "Nothing in this tab", cx + 14, listY + 30, MUTED);

        // ---- bottom bar ----
        text(g, fit(status.getString(), cw - applyW - 16), cx + 2, applyY + 8, MUTED);
        round(g, applyX, applyY, applyW, applyH, applyBtn.isHoveredOrFocused() ? 0xFFFFA23D : ORANGE);
        String a = "Apply & reload";
        bold(g, a, applyX + (applyW - font.width(a) - 1) / 2, applyY + 8, 0xFF1B0F0B);
    }

    @Override
    public void onClose() {
        Config.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
