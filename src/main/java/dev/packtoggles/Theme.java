package dev.packtoggles;

import java.util.List;

/** A colour scheme plus the particle scene that plays behind the panel. */
public record Theme(String id, String name, String sub, int accent, int accent2, int bg, int card, int line, Kind kind) {
    public enum Kind { EMBER, STARS, DIGITAL, SNOW, PETAL, GOLD, BUBBLES, GRID, VOID, RAIN, FOG, FIREFLY }

    private static Theme t(String id, String name, String sub, int a, int a2, int bg, int card, int line, Kind k) {
        return new Theme(id, name, sub, 0xFF000000 | a, 0xFF000000 | a2, 0xFF000000 | bg, 0xFF000000 | card, 0xFF000000 | line, k);
    }

    public static final List<Theme> ALL = List.of(
        t("inferno", "Inferno", "Flames and embers", 0xF08A24, 0xFFC27A, 0x170D0A, 0x24150F, 0x3A2218, Kind.EMBER),
        t("midnight", "Midnight", "Aurora and shooting stars", 0x6C7BFF, 0x2FD0FF, 0x0B0E1F, 0x141934, 0x232B55, Kind.STARS),
        t("emerald", "Emerald", "Digital rain and decode", 0x2ED573, 0xA6FF7A, 0x07140E, 0x0E241A, 0x17402C, Kind.DIGITAL),
        t("frost", "Frost", "Ice crystals and snow", 0x5BC8FF, 0xE6F7FF, 0x0A1420, 0x112133, 0x1D3A55, Kind.SNOW),
        t("sakura", "Sakura", "Blossoms and petals", 0xFF7AB6, 0xFFC1DD, 0x1D0D18, 0x2B1424, 0x4A2340, Kind.PETAL),
        t("gold", "Luxe Gold", "Art deco gold shimmer", 0xF5C542, 0xFFE9A0, 0x15110A, 0x241C0E, 0x453615, Kind.GOLD),
        t("abyss", "Abyss", "Light rays and bubbles", 0x2D9CFF, 0x29E0D0, 0x06121F, 0x0C2036, 0x123456, Kind.BUBBLES),
        t("synthwave", "Synthwave", "Neon grid and sunset", 0xFF3EA5, 0x2FE6FF, 0x120A24, 0x1E1238, 0x3A2268, Kind.GRID),
        t("nebula", "Nebula", "Spiral galaxy and stardust", 0xA66BFF, 0xFF7BD5, 0x100A1F, 0x1B1233, 0x33235C, Kind.VOID),
        t("thunder", "Thunder", "Rain and lightning strikes", 0x8FB2E8, 0xFFE45E, 0x0C111B, 0x151D2D, 0x26354F, Kind.RAIN),
        t("bloodmoon", "Blood Moon", "A red moon and drifting fog", 0xE0453F, 0xFF8A5E, 0x1A0809, 0x2A1113, 0x4A1D20, Kind.FOG),
        t("firefly", "Firefly Forest", "Fireflies in the moonlight", 0xB5E04C, 0xFFE27A, 0x0A150B, 0x12230F, 0x213D1B, Kind.FIREFLY)
    );

    public static Theme byId(String id) {
        for (Theme t : ALL) if (t.id().equals(id)) return t;
        return ALL.get(0);
    }
}
