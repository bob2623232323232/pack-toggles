package dev.packtoggles;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Copies each source pack (zip or folder) to "<name>-toggled.zip", leaving out every file of a disabled group. */
public class PackBuilder {
    public static final String SUFFIX = "-toggled";

    public static Path packsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
    }

    private static boolean isOurs(String n) {
        return n.endsWith(SUFFIX + ".zip") || n.endsWith(SUFFIX + ".pending");
    }

    /** Zip packs and unzipped pack folders in resourcepacks/ (our own output excluded). */
    public static List<String> candidates() {
        try (Stream<Path> s = Files.list(packsDir())) {
            return s.filter(p -> {
                        String n = p.getFileName().toString();
                        if (isOurs(n)) return false;
                        if (Files.isDirectory(p)) return Files.exists(p.resolve("pack.mcmeta"));
                        return n.toLowerCase().endsWith(".zip");
                    })
                    .map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String outputName(String src) {
        String base = src.toLowerCase().endsWith(".zip") ? src.substring(0, src.length() - 4) : src;
        return base + SUFFIX + ".zip";
    }

    /** Packs that are switched on in the game right now (a "-toggled" copy counts as its original). */
    public static Set<String> enabledInGame() {
        Set<String> out = new LinkedHashSet<>();
        List<String> cands = candidates();
        for (String id : Minecraft.getInstance().options.resourcePacks) {
            if (!id.startsWith("file/")) continue;
            String n = id.substring(5);
            if (n.endsWith(SUFFIX + ".zip")) {
                String base = n.substring(0, n.length() - (SUFFIX + ".zip").length());
                if (cands.contains(base + ".zip")) n = base + ".zip";
                else if (cands.contains(base)) n = base;
                else continue;
            }
            if (cands.contains(n)) out.add(n);
        }
        return out;
    }

    /** @return true when every pack was written in place, false when one was locked and will be swapped at next launch. */
    public static boolean buildAll() throws IOException {
        boolean all = true;
        for (String s : Config.sources) all &= build(s);
        return all;
    }

    private static boolean build(String src) throws IOException {
        Path in = packsDir().resolve(src);
        if (!Files.exists(in)) throw new FileNotFoundException(src);
        Path out = packsDir().resolve(outputName(src));
        Path tmp = packsDir().resolve(outputName(src).replace(".zip", ".pending"));
        List<Group> off = Group.ALL.stream().filter(g -> Config.disabled.contains(g.id())).toList();

        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> walk = Files.walk(in)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = in.relativize(p).toString().replace('\\', '/');
                        if (off.stream().anyMatch(g -> g.matches(name))) continue;
                        zo.putNextEntry(new ZipEntry(name));
                        Files.copy(p, zo);
                        zo.closeEntry();
                    }
                }
            } else {
                try (ZipFile zf = new ZipFile(in.toFile())) {
                    for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                        ZipEntry e = en.nextElement();
                        String name = e.getName().replace('\\', '/');
                        if (off.stream().anyMatch(g -> g.matches(name))) continue;
                        zo.putNextEntry(new ZipEntry(e.getName()));
                        if (!e.isDirectory()) zf.getInputStream(e).transferTo(zo);
                        zo.closeEntry();
                    }
                }
            }
        }
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException locked) { // Windows keeps a loaded pack open
            return false;
        }
    }

    /** Swaps the originals for their "-toggled" copies in the game's pack list and reloads. */
    public static void selectToggled(Minecraft mc) {
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> before = new ArrayList<>(repo.getSelectedIds());
        List<String> sel = new ArrayList<>(before);
        Collection<String> avail = repo.getAvailableIds();
        for (String s : Config.sources) {
            String orig = "file/" + s, tog = "file/" + outputName(s);
            if (!avail.contains(tog)) continue;
            int i = sel.indexOf(orig);
            if (i >= 0) sel.set(i, tog);
            else if (!sel.contains(tog)) sel.add(tog);
        }
        if (sel.equals(before)) {
            mc.reloadResourcePacks();
        } else {
            repo.setSelected(sel);
            mc.options.updateResourcePacks(repo);
        }
    }

    /** Called at startup, before packs are opened: finishes a swap that was blocked last time. */
    public static void finishPendingSwap() {
        try (Stream<Path> s = Files.list(packsDir())) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(SUFFIX + ".pending")).toList()) {
                String n = p.getFileName().toString();
                Files.move(p, packsDir().resolve(n.substring(0, n.length() - ".pending".length()) + ".zip"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) { }
    }
}
