package dev.packtoggles;

import java.util.List;

/**
 * Colour themes for the Trim Studio screen. Each theme has a background effect (Kind) drawn by FX.
 * Colours are ARGB.
 */
public record Theme(String id, String name, String sub, int bg, int card, int line, int accent, int accent2, Kind kind) {

    /** Background effect drawn behind the panel by FX. */
    public enum Kind { NONE, STARS, VOID, RAIN, SNOW, PETAL, EMBER, BUBBLE, LEAF }

    public static final List<Theme> ALL = List.of(
            new Theme("ember",    "Ember",     "Warm coal glow",        0xFF1A1210, 0xFF2A1E1A, 0xFF3D2C26, 0xFFF2762B, 0xFFFFD23F, Kind.EMBER),
            new Theme("midnight", "Midnight",  "Stars over a dark sky", 0xFF0C1020, 0xFF161C33, 0xFF252D4D, 0xFF6C8CFF, 0xFFB38CFF, Kind.STARS),
            new Theme("void",     "Void",      "Drifting deep space",   0xFF07060C, 0xFF13111C, 0xFF221E30, 0xFF9B5CFF, 0xFF5CE1FF, Kind.VOID),
            new Theme("storm",    "Storm",     "Rain and lightning",    0xFF10161C, 0xFF1B242D, 0xFF2B3744, 0xFF4FB3E8, 0xFFA6E3FF, Kind.RAIN),
            new Theme("frost",    "Frost",     "Gentle snowfall",       0xFF121A22, 0xFF1E2A36, 0xFF2F3F50, 0xFF8FD3FF, 0xFFFFFFFF, Kind.SNOW),
            new Theme("sakura",   "Sakura",    "Falling petals",        0xFF1E1418, 0xFF2E1F25, 0xFF473039, 0xFFFF8FB8, 0xFFFFD1E0, Kind.PETAL),
            new Theme("ocean",    "Ocean",     "Rising bubbles",        0xFF081A1E, 0xFF10282E, 0xFF1C3D45, 0xFF1BC9B0, 0xFF5CE1FF, Kind.BUBBLE),
            new Theme("forest",   "Forest",    "Drifting leaves",       0xFF0F1810, 0xFF1A271B, 0xFF2B3D2C, 0xFF58D26F, 0xFFC6E86B, Kind.LEAF),
            new Theme("slate",    "Slate",     "Plain and calm",        0xFF16171A, 0xFF222428, 0xFF33363C, 0xFFE0E0E0, 0xFF9AA0A6, Kind.NONE)
    );

    /** Finds a theme by its saved id, falling back to the first one. */
    public static Theme byId(String id) {
        if (id != null) for (Theme t : ALL) if (t.id.equalsIgnoreCase(id.trim())) return t;
        return ALL.get(0);
    }
}
