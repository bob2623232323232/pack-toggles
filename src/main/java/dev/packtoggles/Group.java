package dev.packtoggles;

import java.util.List;

/** One on/off switch: every file whose path starts with one of the prefixes belongs to it. */
public record Group(String id, String label, List<String> prefixes) {
    public boolean matches(String path) {
        for (String p : prefixes) if (path.startsWith(p)) return true;
        return false;
    }

    private static Group g(String id, String label, String... prefixes) {
        return new Group(id, label, List.of(prefixes));
    }

    private static Group ns(String namespace, String label) {
        return g(namespace, label, "assets/" + namespace + "/");
    }

    /** Edit this list to add, remove or re-split toggles. Files matching no group are always kept. */
    public static final List<Group> ALL = List.of(
        g("blocks", "Blocks (states, models, textures)",
            "assets/minecraft/blockstates/", "assets/minecraft/models/block/", "assets/minecraft/textures/block/"),
        g("items", "Items (definitions, models, textures)",
            "assets/minecraft/items/", "assets/minecraft/models/item/", "assets/minecraft/textures/item/"),
        g("gui", "GUI textures", "assets/minecraft/textures/gui/"),
        g("trims", "Armor trims (patterns, materials)",
            "assets/minecraft/textures/trims/", "assets/minecraft/atlases/armor_trims.json"),
        g("entities", "Entity textures", "assets/minecraft/textures/entity/"),
        g("effects", "Potion effect icons", "assets/minecraft/textures/mob_effect/"),
        g("particles", "Particles", "assets/minecraft/textures/particle/"),
        g("ranks", "Rank textures", "assets/minecraft/textures/ranks/"),
        g("misc_tex", "Map, misc, custom and font textures",
            "assets/minecraft/textures/map/", "assets/minecraft/textures/misc/",
            "assets/minecraft/textures/custom/", "assets/minecraft/textures/font/", "assets/minecraft/font/"),
        g("sounds", "Sounds", "assets/minecraft/sounds/"),
        g("shaders", "Shaders", "assets/minecraft/shaders/"),
        g("optifine", "OptiFine / CIT / CEM / CTM data", "assets/minecraft/optifine/"),
        g("lang", "Text changes (language files)", "assets/minecraft/lang/", "assets/minecraft/lang_rpo/"),
        g("carl", "Enchantment icons (carl)", "assets/carl/"),
        g("potions_icons", "Potion icons", "assets/potions_icons/"),
        g("visible_cooldown", "Visible cooldown", "assets/visible_cooldown/"),
        g("entity_features", "Entity features", "assets/entity_features/"),
        g("respackopts", "Respackopts presets", "assets/respackopts/"),
        ns("alexsmobs", "Alex's Mobs"),
        ns("alexsdelight", "Alex's Delight"),
        ns("artifacts", "Artifacts"),
        ns("betterend", "Better End"),
        ns("betternether", "Better Nether"),
        ns("byg", "Oh The Biomes You'll Go"),
        ns("culturaldelights", "Cultural Delights"),
        ns("delightful", "Delightful"),
        ns("farmersdelight", "Farmer's Delight"),
        ns("nethersdelight", "Nether's Delight"),
        ns("galosphere", "Galosphere"),
        ns("naturalist", "Naturalist"),
        ns("quark", "Quark"),
        ns("supplementaries", "Supplementaries"),
        ns("throwingdisk", "Throwing Disk"),
        ns("strength", "Strength"),
        ns("sodium", "Sodium"),
        ns("modmenu", "Mod Menu")
    );
}
