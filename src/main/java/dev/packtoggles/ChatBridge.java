package dev.packtoggles;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Minecraft chat <-> Discord. Records every chat line the client receives (with its sender, when there is one),
 * posts new lines to the Discord chat channel in batches (so Discord's rate limit is respected), and once an hour
 * uploads the collected chat as a .txt file, but only while the player is actually in a world.
 * Live lines go out as embeds: consecutive lines from the same player share one embed whose author icon is that
 * player's head, and up to 10 embeds go in one message, so heads cost no extra requests.
 * Needs Fabric API (ClientReceiveMessageEvents). Started the first time the bot connects.
 */
final class ChatBridge {
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final int MAX_LINES = 20000;
    private static final int EMBED_TEXT = 900;      // characters of chat per embed
    private static final int MESSAGE_TEXT = 5000;   // Discord allows 6000 embed characters per message

    /** One received chat line. id/name are null for system and server messages. */
    record Line(String time, String text, UUID id, String name) {
        String plain() { return time + " " + text; }
    }

    private static final Object LOCK = new Object();
    private static final Deque<Line> OUT = new ArrayDeque<>();       // waiting to be posted live
    private static final Deque<String> SINCE = new ArrayDeque<>();   // lines since the last hourly file
    private static final Deque<Line> RECENT = new ArrayDeque<>();    // shown on the Chat tab
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
        if (message == null || !DiscordBot.isRunning()) return;
        String text = message.getString();
        if (text == null || text.isBlank()) return;
        UUID id = sender != null ? sender.id() : null;
        String name = sender != null ? sender.name() : null;
        Line line = new Line(new SimpleDateFormat("HH:mm:ss").format(new Date()), text.replace('\n', ' '), id, name);
        synchronized (LOCK) {
            RECENT.addLast(line);
            while (RECENT.size() > 40) RECENT.removeFirst();
            if (DiscordBot.logChat) { OUT.addLast(line); while (OUT.size() > 500) OUT.removeFirst(); }
            if (DiscordBot.hourly || DiscordBot.logChat) { SINCE.addLast(line.plain()); while (SINCE.size() > MAX_LINES) SINCE.removeFirst(); }
        }
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
                if (!DiscordBot.isRunning() || !DiscordBot.chatReady()) {
                    synchronized (LOCK) { OUT.clear(); }
                    continue;
                }
                flushLive();
                if (++tick % 20 == 0 && DiscordBot.hourly && inGame() && System.currentTimeMillis() - lastFile >= HOUR_MS) sendLog(false);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                DiscordBot.log("Chat log error: " + e.getMessage());
            }
        }
    }

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
                postEmbeds(embeds);
                embeds = new JsonArray();
                total = 0;
            }
            embeds.add(embed(first, sb.toString()));
            total += size;
        }
        if (embeds.size() > 0) postEmbeds(embeds);
    }

    private static boolean sameSender(Line a, Line b) {
        return Objects.equals(a.id(), b.id()) && Objects.equals(a.name(), b.name());
    }

    private static JsonObject embed(Line who, String text) {
        JsonObject e = new JsonObject();
        JsonObject author = new JsonObject();
        boolean player = who.id() != null || who.name() != null;
        author.addProperty("name", player && who.name() != null ? who.name() : "Server");
        String icon = player ? headUrl(who) : null;
        if (icon != null) author.addProperty("icon_url", icon);
        e.add("author", author);
        e.addProperty("description", "```\n" + text.stripTrailing() + "\n```");
        e.addProperty("color", player && who.id() != null ? (who.id().hashCode() & 0xFFFFFF) | 0x404040 : 0x808080);
        return e;
    }

    /**
     * Head image for the embed. Real (online-mode) accounts have version 4 UUIDs, so the UUID is used;
     * offline-mode servers use name-based UUIDs that the skin service doesn't know, so the name is used instead.
     */
    private static String headUrl(Line l) {
        if (l.id() != null && l.id().version() == 4) return "https://mc-heads.net/avatar/" + l.id().toString().replace("-", "") + "/32";
        if (l.name() != null && !l.name().isBlank()) return "https://mc-heads.net/avatar/" + URLEncoder.encode(l.name(), StandardCharsets.UTF_8) + "/32";
        return null;
    }

    private static String clean(String s) { return s.replace("```", "'''"); }

    private static void postEmbeds(JsonArray embeds) throws Exception {
        DiscordBot.postChatEmbeds(embeds);
        Thread.sleep(600);
    }

    /** Old style: code blocks of plain text (used when "Show player heads in Discord" is off). */
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
