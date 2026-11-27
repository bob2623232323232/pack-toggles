package dev.packtoggles;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Tiny Discord bot over the REST API (no extra libraries): posts trims and skins to a channel and
 * answers !commands by polling that channel every few seconds. A second "chat channel" carries the
 * Minecraft chat log and the !say command (see ChatBridge).
 * The bot needs "Message Content Intent" enabled in the Discord developer portal to read commands.
 * The token is never logged.
 */
public final class DiscordBot {
    static volatile String token = "", channelId = "", userId = "", chatChannelId = "";
    static volatile boolean listen = true, autoPost = false, relay = false;
    static volatile boolean logChat = false, hourly = false, sayOn = true, chatEmbeds = true;
    static volatile boolean lockToken = false, autoConnect = true, scriptsOn = true;

    private static volatile boolean running = false;
    private static volatile String status = "Offline";
    private static final Deque<String> LOG = new ArrayDeque<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static Thread thread;
    private static String lastId = null, lastChatId = null;
    private static boolean warnedIntent = false;

    private DiscordBot() { }

    static boolean isRunning() { return running; }

    static String status() { return status; }

    static synchronized List<String> log() { return new ArrayList<>(LOG); }

    static synchronized void log(String s) {
        LOG.addLast(new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + s);
        while (LOG.size() > 40) LOG.removeFirst();
    }

    static synchronized void start() {
        if (running) return;
        if (token.isBlank() || channelId.isBlank()) { log("Enter a bot token and channel ID first"); return; }
        running = true;
        lastId = null;
        lastChatId = null;
        status = "Connecting...";
        ChatBridge.ensureStarted();
        thread = new Thread(DiscordBot::loop, "trimstudio-discord");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        running = false;
        status = "Offline";
        if (thread != null) thread.interrupt();
        log("Disconnected");
    }

    // ---------------- REST ----------------
    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + token.trim())
                .header("User-Agent", "DiscordBot (https://modrinth.com, 1.0)");
    }

    private static String me() throws Exception {
        HttpResponse<String> r = HTTP.send(req("/users/@me").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception("HTTP " + r.statusCode() + " (check the bot token)");
        return JsonParser.parseString(r.body()).getAsJsonObject().get("username").getAsString();
    }

    /** Message payload that never pings anyone (@everyone, roles, users). */
    private static JsonObject payload(String content) {
        JsonObject o = new JsonObject();
        o.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) : content);
        JsonObject am = new JsonObject();
        am.add("parse", new JsonArray());
        o.add("allowed_mentions", am);
        return o;
    }

    /** How long Discord asked us to wait after a 429, in ms. */
    private static long retryMs(String body) {
        try {
            double s = JsonParser.parseString(body).getAsJsonObject().get("retry_after").getAsDouble();
            return Math.min(30000L, (long) (s * 1000) + 250L);
        } catch (Exception e) {
            return 2000L;
        }
    }

    /** Sends a JSON message, waiting and retrying (twice at most) when rate limited. */
    private static void sendJson(String channel, JsonObject body) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> r = HTTP.send(req("/channels/" + channel.trim() + "/messages")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429 && attempt < 2) { Thread.sleep(retryMs(r.body())); continue; }
            if (r.statusCode() / 100 != 2) throw new Exception("HTTP " + r.statusCode() + " while sending");
            return;
        }
    }

    private static void postTo(String channel, String content) throws Exception { sendJson(channel, payload(content)); }

    private static void post(String content) throws Exception { postTo(channelId, content); }

    private static void postFileTo(String channel, String content, String name, byte[] data, String mime) throws Exception {
        String b = "----trimstudio" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + payload(content) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + name + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(req("/channels/" + channel.trim() + "/messages")
                .header("Content-Type", "multipart/form-data; boundary=" + b)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new Exception("HTTP " + r.statusCode() + " while uploading");
    }

    private static void postFile(String content, String name, byte[] data) throws Exception {
        postFileTo(channelId, content, name, data, "image/png");
    }

    /** Used by ChatBridge (runs on its own thread). Needs the bot token and a chat channel. */
    static boolean chatReady() { return !token.isBlank() && !chatChannelId.isBlank(); }

    static void postChat(String content) throws Exception { postTo(chatChannelId, content); }

    /** Up to 10 embeds in one message (one request), still with mentions disabled. */
    static void postChatEmbeds(JsonArray embeds) throws Exception {
        JsonObject o = payload("");
        o.remove("content");
        o.add("embeds", embeds);
        sendJson(chatChannelId, o);
    }

    /** Embeds plus the attached head images they point at (icon_url "attachment://name.png"). */
    static void postChatEmbedsFiles(JsonArray embeds, java.util.Map<String, byte[]> files) throws Exception {
        JsonObject o = payload("");
        o.remove("content");
        o.add("embeds", embeds);
        for (int attempt = 0; ; attempt++) {
            String b = "----trimstudio" + System.nanoTime();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + o + "\r\n").getBytes(StandardCharsets.UTF_8));
            int n = 0;
            for (java.util.Map.Entry<String, byte[]> f : files.entrySet()) {
                out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"files[" + n++ + "]\"; filename=\"" + f.getKey() + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(f.getValue());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = HTTP.send(req("/channels/" + chatChannelId.trim() + "/messages")
                    .header("Content-Type", "multipart/form-data; boundary=" + b)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429 && attempt < 2) { Thread.sleep(retryMs(r.body())); continue; }
            if (r.statusCode() / 100 != 2) throw new Exception("HTTP " + r.statusCode() + " while sending");
            return;
        }
    }

    static void postChatFile(String content, String name, byte[] data) throws Exception {
        postFileTo(chatChannelId, content, name, data, "text/plain");
    }

    /** Fire-and-forget helpers used by the UI. */
    static void sendText(String text) {
        async(() -> { post(text); log("Sent message to Discord"); });
    }

    static void sendFile(String text, String name, byte[] data) {
        async(() -> { postFile(text, name, data); log("Sent " + name + " to Discord"); });
    }

    static void sendChatText(String text) {
        if (!chatReady()) { log("Enter a bot token and a chat channel ID first"); return; }
        Thread t = new Thread(() -> {
            try { postChat(text); log("Sent to the chat channel"); } catch (Exception e) { log("Failed: " + e.getMessage()); }
        }, "trimstudio-discord-chat");
        t.setDaemon(true);
        t.start();
    }

    private interface Job { void run() throws Exception; }

    private static void async(Job j) {
        if (token.isBlank() || channelId.isBlank()) { log("Enter a bot token and channel ID first"); return; }
        Thread t = new Thread(() -> {
            try { j.run(); } catch (Exception e) { log("Failed: " + e.getMessage()); }
        }, "trimstudio-discord-send");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- polling ----------------
    private static void loop() {
        try {
            String name = me();
            status = "Online as " + name;
            log("Connected as " + name);
        } catch (Exception e) {
            status = "Offline";
            running = false;
            log("Connect failed: " + e.getMessage());
            return;
        }
        while (running) {
            try {
                if (listen) poll();
                if (!chatChannelId.isBlank()) pollChat();
                Thread.sleep(4000);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                log("Error: " + e.getMessage());
                try { Thread.sleep(8000); } catch (InterruptedException ie) { break; }
            }
        }
        status = "Offline";
    }

    private static void poll() throws Exception {
        String path = "/channels/" + channelId.trim() + "/messages?limit=" + (lastId == null ? 1 : 20) + (lastId == null ? "" : "&after=" + lastId);
        HttpResponse<String> r = HTTP.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) { Thread.sleep(retryMs(r.body())); return; }
        if (r.statusCode() != 200) { log("Could not read the channel: HTTP " + r.statusCode()); Thread.sleep(6000); return; }
        JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
        if (lastId == null) {
            lastId = arr.size() > 0 ? arr.get(0).getAsJsonObject().get("id").getAsString() : "0";
            return;
        }
        if (arr.size() == 0) return;
        lastId = arr.get(0).getAsJsonObject().get("id").getAsString();
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonObject m = arr.get(i).getAsJsonObject();
            JsonObject author = m.getAsJsonObject("author");
            if (author.has("bot") && author.get("bot").getAsBoolean()) continue;
            if (!userId.isBlank() && !author.get("id").getAsString().equals(userId.trim())) continue;
            String content = m.get("content").getAsString().trim();
            if (content.isEmpty()) {
                if (!warnedIntent) { warnedIntent = true; log("Messages look empty: enable Message Content Intent for the bot"); }
                continue;
            }
            if (content.startsWith("!")) handle(content);
            else if (relay) showInGame(author, content);
        }
    }

    /** Reads the Minecraft chat channel: only "!say <text>" is acted on. */
    private static void pollChat() throws Exception {
        String ch = chatChannelId.trim();
        String path = "/channels/" + ch + "/messages?limit=" + (lastChatId == null ? 1 : 20) + (lastChatId == null ? "" : "&after=" + lastChatId);
        HttpResponse<String> r = HTTP.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) { Thread.sleep(retryMs(r.body())); return; }
        if (r.statusCode() != 200) { log("Could not read the chat channel: HTTP " + r.statusCode()); Thread.sleep(6000); return; }
        JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
        if (lastChatId == null) {
            lastChatId = arr.size() > 0 ? arr.get(0).getAsJsonObject().get("id").getAsString() : "0";
            return;
        }
        if (arr.size() == 0) return;
        lastChatId = arr.get(0).getAsJsonObject().get("id").getAsString();
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonObject m = arr.get(i).getAsJsonObject();
            JsonObject author = m.getAsJsonObject("author");
            if (author.has("bot") && author.get("bot").getAsBoolean()) continue;
            if (!userId.isBlank() && !author.get("id").getAsString().equals(userId.trim())) continue;
            String content = m.get("content").getAsString().trim();
            if (content.isEmpty()) {
                if (!warnedIntent) { warnedIntent = true; log("Messages look empty: enable Message Content Intent for the bot"); }
                continue;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.equals("!say") || lower.startsWith("!say ")) say(content.length() > 4 ? content.substring(4).trim() : "");
        }
    }

    /** Sends a real chat message as the player. Slash commands are refused on purpose. */
    private static void say(String text) {
        if (!sayOn) { log("!say ignored (turned off on the Chat tab)"); return; }
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return;
        if (t.startsWith("/")) { log("!say refused: messages starting with / are not allowed"); return; }
        if (t.length() > 256) t = t.substring(0, 256);
        final String msg = t;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null && mc.getConnection() != null) mc.getConnection().sendChat(msg);
        });
        log("Said in game: " + (msg.length() > 40 ? msg.substring(0, 40) + ".." : msg));
    }

    /** Shows a normal Discord message in the Minecraft chat (only while "Show Discord chat in game" is on). */
    private static void showInGame(JsonObject author, String content) {
        String who = author.has("global_name") && !author.get("global_name").isJsonNull()
                ? author.get("global_name").getAsString() : author.get("username").getAsString();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Discord] " + who + ": " + content), false);
        });
    }

    /** Runs on the game thread (world data isn't safe to read from here) and waits up to 3 s for the answer. */
    private static String onGame(java.util.function.Supplier<String> s) {
        java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
        Minecraft.getInstance().execute(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.complete("Error: " + t.getMessage()); } });
        try { return f.get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return "The game didn't answer in time"; }
    }

    private static void handle(String content) throws Exception {
        String[] parts = content.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT), args = parts.length > 1 ? parts[1] : "";
        switch (cmd) {
            case "!help" -> post("Commands: `!trim`, `!random`, `!set <armor> <pattern> <material>`, `!players`, `!waypoints`, `!mark <name>`, `!skin`, `!combo`, `!help`\nScripts (only for the set user ID): `!run <steps>`, `!script <name>`, `!scripts`, `!savescript <name> <steps>`, `!delscript <name>`, `!stop`\nSteps: " + Scripts.HELP);
            case "!combo" -> post("Combo **" + Extras.combo + "**, best **" + Extras.bestCombo + "**, hits " + Extras.hits + ", hits taken " + Extras.taken);
            case "!skin" -> {
                java.nio.file.Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("trim-studio-skin.png");
                if (java.nio.file.Files.exists(p)) { postFile("Skin from the Trim Studio editor", "skin.png", java.nio.file.Files.readAllBytes(p)); log("Answered !skin"); }
                else post("No skin saved yet. Use the Skin tab and press Save skin PNG");
            }
            case "!run", "!script", "!scripts", "!savescript", "!delscript", "!stop" -> {
                if (!scriptsOn) post("Scripts are turned off in Trim Studio");
                else if (userId.isBlank()) post("Set your Discord user ID in Trim Studio first, scripts only obey that user");
                else {
                    String out;
                    switch (cmd) {
                        case "!run" -> out = Scripts.run(args);
                        case "!script" -> out = Scripts.runNamed(args.trim());
                        case "!scripts" -> out = Scripts.list();
                        case "!stop" -> out = Scripts.stop();
                        case "!delscript" -> out = Scripts.delete(args.trim());
                        default -> {
                            String[] a = args.trim().split("\\s+", 2);
                            out = a.length < 2 ? "Usage: `!savescript <name> <steps>`" : Scripts.save(a[0], a[1]);
                        }
                    }
                    post(out);
                    log("Answered " + cmd);
                }
            }
            case "!trim" -> { post(ToggleScreen.loadoutText()); log("Answered !trim"); }
            case "!random" -> { ToggleScreen.randomTrim(); post(ToggleScreen.loadoutText()); log("Answered !random"); }
            case "!set" -> { post(ToggleScreen.setLoadout(args)); log("Answered !set"); }
            case "!players" -> { post(onGame(ToggleScreen::playersText)); log("Answered !players"); }
            case "!waypoints", "!wp" -> { post(ToggleScreen.waypointsText()); log("Answered !waypoints"); }
            case "!mark" -> { post(onGame(() -> ToggleScreen.markHere(args.trim()))); log("Answered !mark"); }
            default -> { }
        }
    }
}
