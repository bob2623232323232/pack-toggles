package dev.packtoggles;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import com.google.gson.JsonParser;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Minecraft chat -> Discord. Records every chat line the client receives (with its sender, when
 * there is one). Discord: new lines are posted in batches as embeds with the sender's head, and an hourly .txt file
 * is uploaded while the player is in a world.
 * Needs Fabric API (ClientReceiveMessageEvents). Started when the bot connects.
 */
final class ChatBridge {
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final int MAX_LINES = 20000;
    private static final int EMBED_TEXT = 900;
    private static final int MESSAGE_TEXT = 5000;

    /** One received chat line. id/name are null for system and server messages. */
    record Line(long ms, String time, String text, UUID id, String name) {
        String plain() { return time + " " + text; }
    }

    private static final Object LOCK = new Object();
    private static final Deque<Line> OUT = new ArrayDeque<>();       // waiting to be posted to Discord
    private static final Deque<String> SINCE = new ArrayDeque<>();   // lines since the last hourly file
    private static final Deque<Line> RECENT = new ArrayDeque<>();    // shown on the Discord tab
    /** Real face (from the skin the game downloaded) per player, sent to Discord as an attachment. */
    private static final Map<UUID, byte[]> HEADS = new ConcurrentHashMap<>();
    private static final Pattern SKIN_HASH = Pattern.compile("(?:skins/|texture/)([0-9a-fA-F]{40,64})");
    private static boolean started = false;
    private static long lastFile = System.currentTimeMillis();

    private ChatBridge() { }

    static void ensureStarted() {
        synchronized (LOCK) {
            if (started) return;
            started = true;
        }
        try {
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) record(message, null); });
            ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> record(message, sender));
        } catch (Throwable t) {
            DiscordBot.log("Chat hook failed: " + t.getMessage());
        }
        Thread t = new Thread(ChatBridge::loop, "trimstudio-chat");
        t.setDaemon(true);
        t.start();
    }

    private static void record(Component message, GameProfile sender) {
        boolean bot = DiscordBot.isRunning();
        if (message == null || !bot) return;
        String text = message.getString();
        if (text == null || text.isBlank()) return;
        UUID id = sender != null ? sender.id() : null;
        String name = sender != null ? sender.name() : null;
        if (id == null) {
            PlayerInfo who = findSender(text);
            if (who != null) { id = who.getProfile().id(); name = who.getProfile().name(); }
        }
        if (id != null && bot && DiscordBot.chatEmbeds) requestHead(id);
        long now = System.currentTimeMillis();
        Line line = new Line(now, new SimpleDateFormat("HH:mm:ss").format(new Date(now)), text.replace('\n', ' '), id, name);
        synchronized (LOCK) {
            RECENT.addLast(line);
            while (RECENT.size() > 40) RECENT.removeFirst();
            if (bot && DiscordBot.logChat) { OUT.addLast(line); while (OUT.size() > 500) OUT.removeFirst(); }
            if (bot && (DiscordBot.hourly || DiscordBot.logChat)) { SINCE.addLast(line.plain()); while (SINCE.size() > MAX_LINES) SINCE.removeFirst(); }
        }
    }

    /** Servers often send chat as plain text (no sender): the name is somewhere before the first ':' / '>' / '\u00bb', e.g. "<Name> hi", "[Rank] Name: hi". */
    private static PlayerInfo findSender(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null) return null;
            int cut = -1;
            for (int i = 0; i < Math.min(text.length(), 70); i++) {
                char ch = text.charAt(i);
                if (ch == ':' || ch == '>' || ch == '\u00bb') { cut = i; break; }
            }
            if (cut < 1) return null;
            String prefix = text.substring(0, cut);
            PlayerInfo best = null;
            for (PlayerInfo p : mc.getConnection().getOnlinePlayers()) {
                String n = p.getProfile().name();
                if (n == null || n.isEmpty() || (best != null && n.length() <= best.getProfile().name().length())) continue;
                if (!prefix.contains(n)) continue;   // cheap check first: compiling a regex per player per chat line lagged big servers
                if (Pattern.compile("(^|[^A-Za-z0-9_])" + Pattern.quote(n) + "($|[^A-Za-z0-9_])").matcher(prefix).find()) best = p;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- real skin faces ----------------
    private static final Map<UUID, Long> HEAD_TRY = new ConcurrentHashMap<>();
    private static final ExecutorService HEAD_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trimstudio-heads");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient WEB = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** Starts (at most every 30 s while missing, every 10 min once found) a background job that gets the player's current skin face. */
    private static void requestHead(UUID id) {
        try {
            long now = System.currentTimeMillis();
            Long last = HEAD_TRY.get(id);
            if (last != null && now - last < (HEADS.containsKey(id) ? 600000L : 30000L)) return;
            HEAD_TRY.put(id, now);
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null) return;
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info == null) return;
            String hash = hashFromProfile(info.getProfile());
            if (hash == null) {
                Matcher m = SKIN_HASH.matcher(String.valueOf(info.getSkin()));
                if (m.find()) hash = m.group(1);
            }
            if (hash == null) return;                          // default skin: the web head is used instead
            final String h = hash.toLowerCase(Locale.ROOT);
            HEAD_POOL.execute(() -> {
                byte[] face = faceFromHash(h);
                if (face != null) HEADS.put(id, face);
            });
        } catch (Throwable t) {
            // ignore: the web head is used
        }
    }

    /** The skin's texture hash from the profile's signed "textures" property (works for any server, also skin-changing plugins). */
    static String hashFromProfile(GameProfile profile) {
        try {
            Object props = null;
            for (String name : new String[]{"properties", "getProperties"}) {
                try { props = profile.getClass().getMethod(name).invoke(profile); } catch (Exception ignored) { }
                if (props != null) break;
            }
            if (props == null) return null;
            Object coll = null;
            for (java.lang.reflect.Method m : props.getClass().getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 1) {
                    try { coll = m.invoke(props, "textures"); } catch (Exception ignored) { }
                    if (coll != null) break;
                }
            }
            if (!(coll instanceof Iterable<?> it)) return null;
            for (Object prop : it) {
                String value = null;
                for (String name : new String[]{"value", "getValue"}) {
                    try { value = (String) prop.getClass().getMethod(name).invoke(prop); } catch (Exception ignored) { }
                    if (value != null) break;
                }
                if (value == null) continue;
                String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                JsonObject tex = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
                if (tex == null || !tex.has("SKIN")) continue;
                String url = tex.getAsJsonObject("SKIN").get("url").getAsString();
                int i = url.lastIndexOf('/');
                if (i >= 0 && i < url.length() - 1) return url.substring(i + 1);
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    /** Local copy the game already downloaded, else downloaded from the texture server; then cut out the face. */
    private static byte[] faceFromHash(String hash) {
        try {
            BufferedImage skin = null;
            Path file = FabricLoader.getInstance().getGameDir().resolve("assets").resolve("skins").resolve(hash.substring(0, 2)).resolve(hash);
            if (Files.exists(file)) skin = ImageIO.read(file.toFile());
            if (skin == null) {
                HttpResponse<byte[]> res = WEB.send(HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + hash))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200) skin = ImageIO.read(new ByteArrayInputStream(res.body()));
            }
            if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 16) return null;
            BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int base = skin.getRGB(8 + x, 8 + y), hat = skin.getRGB(40 + x, 8 + y);
                int px = (hat >>> 24) > 0 ? hat : base | 0xFF000000;
                for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) out.setRGB(x * 8 + dx, y * 8 + dy, px);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Full skin image for a texture hash (local copy first, then the texture server). Blocking: call off the game thread. */
    static BufferedImage skinImage(String hash) {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("assets").resolve("skins").resolve(hash.substring(0, 2)).resolve(hash);
            if (Files.exists(file)) { BufferedImage i = ImageIO.read(file.toFile()); if (i != null) return i; }
            HttpResponse<byte[]> res = WEB.send(HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + hash))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200) return ImageIO.read(new ByteArrayInputStream(res.body()));
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    static List<Line> recent() {
        synchronized (LOCK) { return new ArrayList<>(RECENT); }
    }

    private static boolean inGame() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null;
    }

    private static void loop() {
        int tick = 0;
        while (true) {
            try {
                Thread.sleep(3000);
                tick++;
                if (DiscordBot.isRunning() && DiscordBot.chatReady()) {
                    flushLive();
                    if (tick % 20 == 0 && DiscordBot.hourly && inGame() && System.currentTimeMillis() - lastFile >= HOUR_MS) sendLog(false);
                } else {
                    synchronized (LOCK) { OUT.clear(); }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                DiscordBot.log("Chat log error: " + e.getMessage());
            }
        }
    }

    // ---------------- Discord ----------------
    private static void flushLive() throws Exception {
        List<Line> lines = new ArrayList<>();
        synchronized (LOCK) {
            if (!DiscordBot.logChat) { OUT.clear(); return; }
            while (!OUT.isEmpty()) lines.add(OUT.removeFirst());
        }
        if (lines.isEmpty()) return;
        if (DiscordBot.chatEmbeds) flushEmbeds(lines);
        else flushPlain(lines);
    }

    /** Groups consecutive lines by sender into embeds with the sender's head as the author icon. */
    private static void flushEmbeds(List<Line> lines) throws Exception {
        JsonArray embeds = new JsonArray();
        Map<String, byte[]> files = new LinkedHashMap<>();
        int total = 0, i = 0;
        while (i < lines.size()) {
            Line first = lines.get(i);
            StringBuilder sb = new StringBuilder();
            while (i < lines.size() && sameSender(first, lines.get(i))) {
                String s = clean(lines.get(i).plain());
                if (s.length() > EMBED_TEXT) s = s.substring(0, EMBED_TEXT);
                if (sb.length() > 0 && sb.length() + s.length() + 1 > EMBED_TEXT) break;
                sb.append(s).append('\n');
                i++;
            }
            int size = sb.length() + 60;
            if (embeds.size() == 10 || (embeds.size() > 0 && total + size > MESSAGE_TEXT)) {
                postEmbeds(embeds, files);
                embeds = new JsonArray();
                files = new LinkedHashMap<>();
                total = 0;
            }
            embeds.add(embed(first, sb.toString(), files));
            total += size;
        }
        if (embeds.size() > 0) postEmbeds(embeds, files);
    }

    private static boolean sameSender(Line a, Line b) {
        return Objects.equals(a.id(), b.id()) && Objects.equals(a.name(), b.name());
    }

    private static JsonObject embed(Line who, String text, Map<String, byte[]> files) {
        JsonObject e = new JsonObject();
        JsonObject author = new JsonObject();
        boolean player = who.id() != null || who.name() != null;
        author.addProperty("name", player && who.name() != null ? who.name() : "Server");
        byte[] face = who.id() != null ? HEADS.get(who.id()) : null;
        String icon;
        if (face != null) {
            String fn = "head-" + who.id().toString().replace("-", "") + ".png";
            files.put(fn, face);
            icon = "attachment://" + fn;
        } else {
            icon = player ? headUrl(who) : null;
        }
        if (icon != null) author.addProperty("icon_url", icon);
        e.add("author", author);
        e.addProperty("description", "```\n" + text.stripTrailing() + "\n```");
        e.addProperty("color", player && who.id() != null ? (who.id().hashCode() & 0xFFFFFF) | 0x404040 : 0x808080);
        return e;
    }

    /** Online-mode accounts have version 4 UUIDs; offline-mode servers don't, so the name is used instead. */
    private static String headUrl(Line l) {
        if (l.id() != null && l.id().version() == 4) return "https://mc-heads.net/avatar/" + l.id().toString().replace("-", "") + "/32";
        if (l.name() != null && !l.name().isBlank()) return "https://mc-heads.net/avatar/" + URLEncoder.encode(l.name(), StandardCharsets.UTF_8) + "/32";
        return null;
    }

    private static String clean(String s) { return s.replace("```", "'''"); }

    private static void postEmbeds(JsonArray embeds, Map<String, byte[]> files) throws Exception {
        if (files.isEmpty()) DiscordBot.postChatEmbeds(embeds);
        else DiscordBot.postChatEmbedsFiles(embeds, files);
        Thread.sleep(600);
    }

    private static void flushPlain(List<Line> lines) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            String s = clean(l.plain());
            if (s.length() > 1700) s = s.substring(0, 1700);
            if (sb.length() + s.length() + 1 > 1700) { postBlock(sb.toString()); sb.setLength(0); }
            sb.append(s).append('\n');
        }
        if (sb.length() > 0) postBlock(sb.toString());
    }

    private static void postBlock(String text) throws Exception {
        DiscordBot.postChat("```\n" + text.stripTrailing() + "\n```");
        Thread.sleep(600);
    }

    /** Uploads the collected chat as a text file. "manual" skips the in-game check. */
    static void sendLog(boolean manual) {
        if (!DiscordBot.chatReady()) { DiscordBot.log("Enter a bot token and a chat channel ID first"); return; }
        List<String> lines;
        synchronized (LOCK) {
            lines = new ArrayList<>(SINCE);
        }
        if (lines.isEmpty()) {
            if (manual) DiscordBot.log("No chat collected yet (turn on the hourly file and chat in game)");
            return;
        }
        String name = "minecraft-chat-" + new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date()) + ".txt";
        byte[] data = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        try {
            DiscordBot.postChatFile("Minecraft chat log (" + lines.size() + " lines)", name, data);
            synchronized (LOCK) {
                for (int i = 0; i < lines.size() && !SINCE.isEmpty(); i++) SINCE.removeFirst();
            }
            lastFile = System.currentTimeMillis();
            DiscordBot.log("Sent " + name);
        } catch (Exception e) {
            DiscordBot.log("Chat file failed: " + e.getMessage());
        }
    }

    /** For the "Send log now" button. */
    static void sendLogNow() {
        Thread t = new Thread(() -> sendLog(true), "trimstudio-chat-file");
        t.setDaemon(true);
        t.start();
    }
}
