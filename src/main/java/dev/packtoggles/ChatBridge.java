package dev.packtoggles;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;

/**
 * Minecraft chat <-> Discord. Records every chat line the client receives, posts new lines to the
 * Discord chat channel in batches (so Discord's rate limit is respected), and once an hour uploads the
 * collected chat as a .txt file, but only while the player is actually in a world.
 * Needs Fabric API (ClientReceiveMessageEvents). Started the first time the bot connects.
 */
final class ChatBridge {
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final int MAX_LINES = 20000;

    private static final Object LOCK = new Object();
    private static final Deque<String> OUT = new ArrayDeque<>();     // waiting to be posted live
    private static final Deque<String> SINCE = new ArrayDeque<>();   // lines since the last hourly file
    private static final Deque<String> RECENT = new ArrayDeque<>();  // shown on the Chat tab
    private static boolean started = false;
    private static long lastFile = System.currentTimeMillis();

    private ChatBridge() { }

    static void ensureStarted() {
        synchronized (LOCK) {
            if (started) return;
            started = true;
        }
        try {
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) record(message.getString()); });
            ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> record(message.getString()));
        } catch (Throwable t) {
            DiscordBot.log("Chat hook failed: " + t.getMessage());
        }
        Thread t = new Thread(ChatBridge::loop, "trimstudio-chat");
        t.setDaemon(true);
        t.start();
    }

    private static void record(String text) {
        if (text == null || text.isBlank() || !DiscordBot.isRunning()) return;
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + text.replace('\n', ' ');
        synchronized (LOCK) {
            RECENT.addLast(line);
            while (RECENT.size() > 40) RECENT.removeFirst();
            if (DiscordBot.logChat) { OUT.addLast(line); while (OUT.size() > 500) OUT.removeFirst(); }
            if (DiscordBot.hourly || DiscordBot.logChat) { SINCE.addLast(line); while (SINCE.size() > MAX_LINES) SINCE.removeFirst(); }
        }
    }

    static List<String> recent() {
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

    /** Posts waiting lines as code blocks (keeps Discord formatting and mentions from messing up the log). */
    private static void flushLive() throws Exception {
        List<String> lines = new ArrayList<>();
        synchronized (LOCK) {
            if (!DiscordBot.logChat) { OUT.clear(); return; }
            while (!OUT.isEmpty()) lines.add(OUT.removeFirst());
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            String s = l.replace("```", "'''");
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
