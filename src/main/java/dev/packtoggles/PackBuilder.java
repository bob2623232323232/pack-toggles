package dev.packtoggles;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Copies the source pack zip into "<name>-toggled.zip", leaving out every file of a disabled group. */
public class PackBuilder {
    public static final String SUFFIX = "-toggled";

    public static Path packsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
    }

    /** Zips in resourcepacks/ that are not our own output. */
    public static List<String> candidates() {
        try (Stream<Path> s = Files.list(packsDir())) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".zip") && !n.endsWith(SUFFIX + ".zip") && !n.endsWith(SUFFIX + ".pending"))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String outputName() {
        String n = Config.source;
        return n.substring(0, n.length() - 4) + SUFFIX + ".zip";
    }

    /** @return true when applied now, false when the pack is locked and will be swapped in on next launch. */
    public static boolean build() throws IOException {
        Path src = packsDir().resolve(Config.source);
        if (!Files.isRegularFile(src)) throw new FileNotFoundException(Config.source);
        Path out = packsDir().resolve(outputName());
        Path tmp = packsDir().resolve(outputName().replace(".zip", ".pending"));

        List<Group> off = Group.ALL.stream().filter(g -> Config.disabled.contains(g.id())).toList();
        try (ZipFile in = new ZipFile(src.toFile());
             ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (Enumeration<? extends ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (off.stream().anyMatch(g -> g.matches(name))) continue;
                zo.putNextEntry(new ZipEntry(e.getName()));
                if (!e.isDirectory()) in.getInputStream(e).transferTo(zo);
                zo.closeEntry();
            }
        }
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException locked) { // Windows keeps the loaded pack open
            return false;
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
