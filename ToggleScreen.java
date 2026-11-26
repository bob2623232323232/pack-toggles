package dev.packtoggles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ToggleScreen extends Screen {
    private static final int PER_PAGE = 10, W = 260, H = 20, GAP = 4;
    private final Screen parent;
    private int page = 0;
    private Component status = Component.empty();

    public ToggleScreen(Screen parent) {
        super(Component.translatable("screen.packtoggles.title"));
        this.parent = parent;
        if (Config.source.isEmpty()) {
            List<String> c = PackBuilder.candidates();
            if (!c.isEmpty()) Config.source = c.get(0);
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        int x = (width - W) / 2, y = 44;

        addRenderableWidget(Button.builder(Component.literal("Source pack: " + (Config.source.isEmpty() ? "(none)" : Config.source)),
                b -> cycleSource()).bounds(x, 20, W, H).build());

        int pages = (Group.ALL.size() + PER_PAGE - 1) / PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= Group.ALL.size()) break;
            Group g = Group.ALL.get(idx);
            addRenderableWidget(Button.builder(label(g), b -> {
                if (!Config.disabled.remove(g.id())) Config.disabled.add(g.id());
                b.setMessage(label(g));
            }).bounds(x, y + i * (H + GAP), W, H).build());
        }

        int by = y + PER_PAGE * (H + GAP) + 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = (page + pages - 1) % pages; init(); })
                .bounds(x, by, 40, H).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = (page + 1) % pages; init(); })
                .bounds(x + W - 40, by, 40, H).build());
        addRenderableWidget(Button.builder(Component.literal("Apply & reload"), b -> apply())
                .bounds(x + 44, by, W - 88, H).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x, by + H + GAP, W, H).build());
    }

    private static Component label(Group g) {
        boolean on = !Config.disabled.contains(g.id());
        return Component.literal((on ? "§a[ON]  §r" : "§c[OFF] §r") + g.label());
    }

    private void cycleSource() {
        List<String> c = PackBuilder.candidates();
        if (c.isEmpty()) { status = Component.literal("§cNo .zip packs in resourcepacks/"); return; }
        Config.source = c.get((Math.max(0, c.indexOf(Config.source)) + 1) % c.size());
        init();
    }

    private void apply() {
        if (Config.source.isEmpty()) { status = Component.literal("§cPick a source pack first"); return; }
        Config.save();
        try {
            boolean now = PackBuilder.build();
            if (now) {
                status = Component.literal("§aBuilt " + PackBuilder.outputName() + " – select it once in Resource Packs");
                Minecraft.getInstance().reloadResources();
            } else {
                status = Component.literal("§eThe pack is in use. Restart the game to apply.");
            }
        } catch (Exception e) {
            PackTogglesClient.LOG.warn("Build failed", e);
            status = Component.literal("§cFailed: " + e.getMessage());
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        g.drawCenteredString(font, status, width / 2, height - 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Config.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
