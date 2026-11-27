package dev.packtoggles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tiny Discord bot with no extra libraries.
 * <ul>
 *   <li>Slash commands (/trim, /set, /run, /say, /fit ...): the bot keeps a Gateway connection (Java's built-in
 *       WebSocket) and answers interactions. This needs NO "Message Content Intent". The commands are registered
 *       for your server when the bot connects, so they show up straight away.</li>
 *   <li>Posting: trims, files and the Minecraft chat log go out over the REST API.</li>
 *   <li>"Show Discord chat in game" still reads normal messages from the channel, which does need Message Content Intent.</li>
 * </ul>
 * The bot must be invited with the {@code applications.commands} scope (the Bot page has a "Copy invite link" button).
 * The token is never logged.
 */
public final class DiscordBot {
    static volatile String token = "", channelId = "", userId = "", chatChannelId = "";
    /** Now means "accept slash commands". */
    static volatile boolean listen = true, autoPost = false, relay = false;
    static volatile boolean logChat = false, hourly = false, sayOn = true, chatEmbeds = true;
    static volatile boolean lockToken = false, autoConnect = true, scriptsOn = true;

    private static final String API = "https://discord.com/api/v10";
    private static final String GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";
    /** View channel, send messages, embed links, attach files, read message history. */
    private static final int INVITE_PERMS = 117760;
    private static final Set<String> SENSITIVE = Set.of("run", "script", "scripts", "savescript", "delscript", "stop", "say");

    private static volatile boolean running = false;
    private static volatile int gen = 0;
    private static volatile String status = "Offline";
    private static volatile String appId = "", guildId = "", botName = "";
    private static volatile boolean fatal = false;
    private static volatile Gateway current;
    private static final Deque<String> LOG = new ArrayDeque<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "trimstudio-discord-cmd");
        t.setDaemon(true);
        return t;
    });
    private static Thread mainThread, pollThread;
    private static String lastId = null;
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
        if (token.isBlank() || (channelId.isBlank() && chatChannelId.isBlank())) { log("Enter a bot token and channel ID first"); return; }
        running = true;
        fatal = false;
        lastId = null;
        final int my = ++gen;
        status = "Connecting...";
        ChatBridge.ensureStarted();
        mainThread = new Thread(() -> mainLoop(my), "trimstudio-discord");
        mainThread.setDaemon(true);
        mainThread.start();
        pollThread = new Thread(() -> pollLoop(my), "trimstudio-discord-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    static synchronized void stop() {
        running = false;
        gen++;
        status = "Offline";
        Gateway g = current;
        if (g != null) g.closeNow();
        if (mainThread != null) mainThread.interrupt();
        if (pollThread != null) pollThread.interrupt();
        log("Disconnected");
    }

    private static boolean alive(int my) { return running && gen == my; }

    // ---------------- invite link ----------------
    /** The bot's user id is the first part of its token (base64), so the link works before connecting. */
    private static String idFromToken(String t) {
        try {
            String p = t.trim().split("\\.")[0];
            String id;
            try { id = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { id = new String(Base64.getDecoder().decode(p), StandardCharsets.UTF_8); }
            return id.matches("\\d{15,25}") ? id : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Link that adds the bot with the slash-command scope, or "" while no valid token is entered. */
    static String inviteUrl() {
        String id = appId.isBlank() ? idFromToken(token) : appId;
        if (id.isBlank()) return "";
        return "https://discord.com/oauth2/authorize?client_id=" + id + "&scope=bot%20applications.commands&permissions=" + INVITE_PERMS;
    }

    // ---------------- REST ----------------
    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + token.trim())
                .header("User-Agent", "DiscordBot (https://modrinth.com, 1.0)");
    }

    /** Interaction endpoints are authorised by the interaction token in the URL, no bot header needed. */
    private static HttpRequest.Builder plain(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "DiscordBot (https://modrinth.com, 1.0)");
    }

    private static JsonObject me() throws Exception {
        HttpResponse<String> r = HTTP.send(req("/users/@me").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception("HTTP " + r.statusCode() + " (check the bot token)");
        return JsonParser.parseString(r.body()).getAsJsonObject();
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
    static void postChatEmbedsFiles(JsonArray embeds, Map<String, byte[]> files) throws Exception {
        JsonObject o = payload("");
        o.remove("content");
        o.add("embeds", embeds);
        for (int attempt = 0; ; attempt++) {
            String b = "----trimstudio" + System.nanoTime();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + o + "\r\n").getBytes(StandardCharsets.UTF_8));
            int n = 0;
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
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

    // ---------------- connecting ----------------
    private static void mainLoop(int my) {
        try {
            JsonObject u = me();
            appId = u.get("id").getAsString();
            botName = u.get("username").getAsString();
            log("Connected as " + botName);
        } catch (Exception e) {
            if (gen == my) { status = "Offline"; running = false; }
            log("Connect failed: " + e.getMessage());
            return;
        }
        resolveGuild();
        registerCommands();
        int backoff = 3000;
        while (alive(my)) {
            boolean ready = false;
            try {
                ready = gatewaySession(my);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                log("Gateway: " + e.getMessage());
            }
            if (fatal || !alive(my)) break;
            status = "Reconnecting...";
            backoff = ready ? 3000 : Math.min(30000, backoff * 2);
            try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
        }
        if (gen == my) { status = "Offline"; running = false; }
    }

    /** The guild id comes from the channel, so the commands can be registered for that server (instant). */
    private static void resolveGuild() {
        guildId = "";
        String ch = !channelId.isBlank() ? channelId.trim() : chatChannelId.trim();
        try {
            HttpResponse<String> r = HTTP.send(req("/channels/" + ch).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                if (o.has("guild_id") && !o.get("guild_id").isJsonNull()) guildId = o.get("guild_id").getAsString();
            } else {
                log("Can't see that channel (HTTP " + r.statusCode() + "): invite the bot and give it View Channel");
            }
        } catch (Exception e) {
            log("Could not look up the server: " + e.getMessage());
        }
    }

    private static boolean putCommands(String path, JsonArray defs) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> r = HTTP.send(req(path).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(defs.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429 && attempt < 2) { Thread.sleep(retryMs(r.body())); continue; }
            if (r.statusCode() / 100 == 2) return true;
            log("Slash command setup: HTTP " + r.statusCode());
            return false;
        }
    }

    private static void registerCommands() {
        try {
            JsonArray defs = commandDefs();
            boolean ok = false;
            if (!guildId.isBlank()) {
                ok = putCommands("/applications/" + appId + "/guilds/" + guildId + "/commands", defs);
                if (!ok) log("Re-invite the bot with the applications.commands scope (Copy invite link)");
            }
            if (!ok) ok = putCommands("/applications/" + appId + "/commands", defs);
            log(ok ? "Slash commands ready (" + defs.size() + ")" + (guildId.isBlank() ? ", global ones can take a while to appear" : "")
                    : "Slash commands could not be set up");
        } catch (Exception e) {
            log("Slash command setup failed: " + e.getMessage());
        }
    }

    // ---------------- command definitions ----------------
    private static JsonObject opt(String name, String desc, int type, boolean required) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.addProperty("type", type);   // 3 = string, 4 = integer
        o.addProperty("required", required);
        return o;
    }

    private static JsonObject choices(JsonObject o, String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            JsonObject c = new JsonObject();
            c.addProperty("name", v);
            c.addProperty("value", v.toLowerCase(Locale.ROOT));
            a.add(c);
        }
        o.add("choices", a);
        return o;
    }

    private static JsonObject range(JsonObject o, int min, int max) {
        o.addProperty("min_value", min);
        o.addProperty("max_value", max);
        return o;
    }

    private static JsonObject cmd(String name, String desc, JsonObject... options) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.addProperty("type", 1);
        o.addProperty("dm_permission", false);
        if (options.length > 0) {
            JsonArray a = new JsonArray();
            for (JsonObject x : options) a.add(x);
            o.add("options", a);
        }
        return o;
    }

    private static JsonArray commandDefs() {
        JsonArray a = new JsonArray();
        a.add(cmd("help", "Show what this bot can do"));
        a.add(cmd("trim", "Show the armor trim picked in Trim Studio"));
        a.add(cmd("random", "Roll a random armor trim"));
        a.add(cmd("set", "Pick the armor, pattern and material",
                choices(opt("armor", "Armor type", 3, false), ToggleScreen.ARMOR_NAMES),
                choices(opt("pattern", "Trim pattern", 3, false), ToggleScreen.PATTERNS),
                choices(opt("material", "Trim material", 3, false), ToggleScreen.MATS)));
        a.add(cmd("players", "List the players on the server you are on"));
        a.add(cmd("combo", "Show your combo tracker"));
        a.add(cmd("run", "Run steps on your player", opt("steps", "For example: walk 3; jump; run 2", 3, true)));
        a.add(cmd("script", "Run a saved script", opt("name", "Name of the saved script", 3, true)));
        a.add(cmd("scripts", "List the saved scripts"));
        a.add(cmd("savescript", "Save a script",
                opt("name", "1-24 letters, numbers, - or _", 3, true), opt("steps", "The steps, separated by ;", 3, true)));
        a.add(cmd("delscript", "Delete a saved script", opt("name", "Name of the saved script", 3, true)));
        a.add(cmd("stop", "Stop the running script"));
        a.add(cmd("say", "Say something in the Minecraft chat as you", opt("message", "What to say (no / commands)", 3, true)));
        a.add(cmd("fit", "Roll a random armor trim and get a fit score"));
        a.add(cmd("roll", "Roll dice",
                range(opt("sides", "Sides per die (default 20)", 4, false), 2, 1000),
                range(opt("dice", "How many dice (default 1)", 4, false), 1, 10)));
        a.add(cmd("flip", "Flip a coin"));
        a.add(cmd("8ball", "Ask the magic 8-ball", opt("question", "Your question", 3, true)));
        a.add(cmd("rps", "Rock paper scissors against the bot",
                choices(opt("choice", "Your move", 3, true), "Rock", "Paper", "Scissors")));
        return a;
    }

    // ---------------- Gateway (WebSocket) ----------------
    private static final class Gateway implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private final Object sendLock = new Object();
        final CountDownLatch done = new CountDownLatch(1);
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trimstudio-gateway");
            t.setDaemon(true);
            return t;
        });
        private volatile WebSocket ws;
        private volatile long seq = -1;
        private volatile boolean acked = true;
        volatile boolean ready = false;
        volatile int closeCode = 0;

        @Override
        public void onOpen(WebSocket w) {
            ws = w;
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try { onMessage(msg); } catch (Exception e) { log("Gateway message error: " + e.getMessage()); }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            closeCode = code;
            try { w.sendClose(WebSocket.NORMAL_CLOSURE, ""); } catch (Exception ignored) { }
            shutdown();
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) { shutdown(); }

        private void onMessage(String msg) {
            JsonObject o = JsonParser.parseString(msg).getAsJsonObject();
            JsonElement s = o.get("s");
            if (s != null && !s.isJsonNull()) seq = s.getAsLong();
            int op = o.get("op").getAsInt();
            switch (op) {
                case 10 -> {
                    final long interval = Math.max(1000, o.getAsJsonObject("d").get("heartbeat_interval").getAsLong());
                    exec.execute(() -> {
                        send(identify());
                        exec.scheduleAtFixedRate(this::beat, (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                    });
                }
                case 11 -> acked = true;
                case 1 -> exec.execute(this::beat);
                case 7, 9 -> closeNow();    // reconnect / invalid session: start over
                case 0 -> {
                    String t = o.has("t") && !o.get("t").isJsonNull() ? o.get("t").getAsString() : "";
                    if (t.equals("READY")) {
                        ready = true;
                        status = "Online as " + botName;
                        log("Slash commands are live");
                    } else if (t.equals("INTERACTION_CREATE")) {
                        final JsonObject d = o.getAsJsonObject("d");
                        WORKERS.execute(() -> onInteraction(d));
                    }
                }
                default -> { }
            }
        }

        private String identify() {
            JsonObject props = new JsonObject();
            props.addProperty("os", "java");
            props.addProperty("browser", "trimstudio");
            props.addProperty("device", "trimstudio");
            JsonObject d = new JsonObject();
            d.addProperty("token", token.trim());
            d.addProperty("intents", 0);       // interactions need no intents at all
            d.add("properties", props);
            JsonObject o = new JsonObject();
            o.addProperty("op", 2);
            o.add("d", d);
            return o.toString();
        }

        private void beat() {
            if (!acked) { log("Gateway stopped answering, reconnecting"); closeNow(); return; }
            acked = false;
            send("{\"op\":1,\"d\":" + (seq < 0 ? "null" : String.valueOf(seq)) + "}");
        }

        private void send(String text) {
            try {
                synchronized (sendLock) { ws.sendText(text, true).get(5, TimeUnit.SECONDS); }
            } catch (Exception e) {
                closeNow();
            }
        }

        void closeNow() {
            try { WebSocket w = ws; if (w != null) w.abort(); } catch (Exception ignored) { }
            shutdown();
        }

        private void shutdown() {
            exec.shutdownNow();
            done.countDown();
        }
    }

    /** One Gateway connection. Returns true when it got as far as READY. */
    private static boolean gatewaySession(int my) throws Exception {
        Gateway gw = new Gateway();
        current = gw;
        HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(GATEWAY), gw).get(15, TimeUnit.SECONDS);
        while (alive(my) && !gw.done.await(1, TimeUnit.SECONDS)) { /* wait until it closes */ }
        gw.closeNow();
        int c = gw.closeCode;
        if (c == 4004) { fatal = true; log("Discord rejected the bot token"); }
        else if (c == 4012 || c == 4013 || c == 4014) { fatal = true; log("Discord closed the connection (code " + c + ")"); }
        return gw.ready;
    }

    // ---------------- slash commands ----------------
    private static String userOf(JsonObject d) {
        try {
            JsonObject u = d.has("member") && !d.get("member").isJsonNull()
                    ? d.getAsJsonObject("member").getAsJsonObject("user") : d.getAsJsonObject("user");
            return u.get("id").getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> options(JsonObject data) {
        Map<String, String> m = new HashMap<>();
        if (data.has("options") && data.get("options").isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray("options")) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("name") && o.has("value")) m.put(o.get("name").getAsString(), o.get("value").getAsString());
            }
        }
        return m;
    }

    /** null when allowed, else the reason. */
    private static String denied(String cmd, String uid) {
        if (!listen) return "Commands are turned off in Trim Studio.";
        if (!userId.isBlank() && !uid.equals(userId.trim())) return "Only the Discord user set in Trim Studio can use this bot.";
        if (SENSITIVE.contains(cmd) && userId.isBlank()) return "Set your Discord user ID in Trim Studio first. This command controls your game, so it only obeys that user.";
        if (cmd.equals("say") && !sayOn) return "/say is turned off in Trim Studio (Chat page).";
        if (SENSITIVE.contains(cmd) && !cmd.equals("say") && !scriptsOn) return "Scripts are turned off in Trim Studio.";
        if ((cmd.equals("fit") || cmd.equals("roll") || cmd.equals("flip") || cmd.equals("8ball") || cmd.equals("rps")) && !Fun.on)
            return "Fun commands are turned off in Trim Studio (Fun page).";
        return null;
    }

    private static boolean callback(JsonObject d, int type, String content, boolean ephemeral) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        if (content != null || ephemeral) {
            JsonObject data = content != null ? payload(content) : new JsonObject();
            if (ephemeral) data.addProperty("flags", 64);
            body.add("data", data);
        }
        HttpResponse<String> r = HTTP.send(plain("/interactions/" + d.get("id").getAsString() + "/" + d.get("token").getAsString() + "/callback")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) { log("Could not answer a command (HTTP " + r.statusCode() + ")"); return false; }
        return true;
    }

    private static void editReply(JsonObject d, String content) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> r = HTTP.send(plain("/webhooks/" + appId + "/" + d.get("token").getAsString() + "/messages/@original")
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload(content).toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429 && attempt < 2) { Thread.sleep(retryMs(r.body())); continue; }
            if (r.statusCode() / 100 != 2) log("Could not send a reply (HTTP " + r.statusCode() + ")");
            return;
        }
    }

    private static void onInteraction(JsonObject d) {
        try {
            if (!d.has("type") || d.get("type").getAsInt() != 2) return;    // 2 = slash command
            JsonObject data = d.getAsJsonObject("data");
            String cmd = data.get("name").getAsString().toLowerCase(Locale.ROOT);
            String uid = userOf(d);
            String no = denied(cmd, uid);
            if (no != null) { callback(d, 4, no, true); return; }
            boolean quiet = SENSITIVE.contains(cmd);                          // only the user sees script / say replies
            if (!callback(d, 5, null, quiet)) return;                         // "thinking...", gives us more than 3 seconds
            String out;
            try { out = run(cmd, options(data)); } catch (Exception e) { out = "Something went wrong: " + e.getMessage(); }
            editReply(d, out == null || out.isBlank() ? "Done" : out);
            log("Answered /" + cmd);
        } catch (Exception e) {
            log("Command error: " + e.getMessage());
        }
    }

    private static String run(String cmd, Map<String, String> o) {
        switch (cmd) {
            case "help":
                return "**Trim Studio bot**\n`/trim` `/random` `/set` `/players` `/combo`\n"
                        + "Scripts (only for the set user ID): `/run` `/script` `/scripts` `/savescript` `/delscript` `/stop`\n"
                        + "In game: `/say`\nFun: `/fit` `/roll` `/flip` `/8ball` `/rps`\nSteps: " + Scripts.HELP;
            case "trim": return ToggleScreen.loadoutText();
            case "random": return ToggleScreen.randomTrim();
            case "set": {
                StringBuilder sb = new StringBuilder();
                for (String k : new String[]{"armor", "pattern", "material"}) if (o.containsKey(k)) sb.append(o.get(k)).append(' ');
                return sb.length() == 0 ? "Pick at least one option. Right now: " + ToggleScreen.loadoutText() : ToggleScreen.setLoadout(sb.toString());
            }
            case "players": return onGame(ToggleScreen::playersText);
            case "combo": return "Combo **" + Extras.combo + "**, best **" + Extras.bestCombo + "**, hits " + Extras.hits + ", hits taken " + Extras.taken;
            case "run": return Scripts.run(o.get("steps"));
            case "script": return Scripts.runNamed(o.getOrDefault("name", "").trim());
            case "scripts": return Scripts.list();
            case "savescript": return Scripts.save(o.get("name"), o.get("steps"));
            case "delscript": return Scripts.delete(o.get("name"));
            case "stop": return Scripts.stop();
            case "say": return say(o.getOrDefault("message", ""));
            case "fit": return Fun.fit();
            case "roll": return Fun.roll(num(o.get("sides"), 20), num(o.get("dice"), 1));
            case "flip": return Fun.flip();
            case "8ball": return Fun.eightBall(o.get("question"));
            case "rps": return Fun.rps(o.get("choice"));
            default: return "I don't know that command yet";
        }
    }

    private static int num(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Sends a real chat message as the player. Messages starting with / are refused on purpose. */
    private static String say(String text) {
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return "Say what?";
        if (t.startsWith("/")) return "Messages starting with / are not allowed";
        if (t.length() > 256) t = t.substring(0, 256);
        final String msg = t;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null && mc.getConnection() != null) mc.getConnection().sendChat(msg);
        });
        return "Said in game: " + msg;
    }

    /** Runs on the game thread (world data isn't safe to read from here) and waits up to 3 s for the answer. */
    private static String onGame(Supplier<String> s) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.complete("Error: " + t.getMessage()); } });
        try { return f.get(3, TimeUnit.SECONDS); } catch (Exception e) { return "The game didn't answer in time"; }
    }

    // ---------------- show Discord chat in game (the only part that reads normal messages) ----------------
    private static void pollLoop(int my) {
        while (alive(my)) {
            try {
                if (relay && !channelId.isBlank()) pollRelay(); else lastId = null;
                Thread.sleep(relay ? 4000 : 1500);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                log("Error: " + e.getMessage());
                try { Thread.sleep(8000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private static void pollRelay() throws Exception {
        String path = "/channels/" + channelId.trim() + "/messages?limit=" + (lastId == null ? 1 : 20) + (lastId == null ? "" : "&after=" + lastId);
        HttpResponse<String> r = HTTP.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) { Thread.sleep(retryMs(r.body())); return; }
        if (r.statusCode() != 200) { log("Could not read the channel: HTTP " + r.statusCode()); Thread.sleep(6000); return; }
        List<JsonObject> msgs = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonArray()) msgs.add(e.getAsJsonObject());
        // oldest first, no matter which order Discord used
        msgs.sort((a, b) -> Long.compare(Long.parseLong(a.get("id").getAsString()), Long.parseLong(b.get("id").getAsString())));
        if (lastId == null) {
            lastId = msgs.isEmpty() ? "0" : msgs.get(msgs.size() - 1).get("id").getAsString();
            return;
        }
        if (msgs.isEmpty()) return;
        lastId = msgs.get(msgs.size() - 1).get("id").getAsString();
        for (JsonObject m : msgs) {
            JsonObject author = m.getAsJsonObject("author");
            if (author.has("bot") && author.get("bot").getAsBoolean()) continue;
            if (!userId.isBlank() && !author.get("id").getAsString().equals(userId.trim())) continue;
            String content = m.get("content").getAsString().trim();
            if (content.isEmpty()) {
                if (!warnedIntent) { warnedIntent = true; log("Messages look empty: enable Message Content Intent for the bot"); }
                continue;
            }
            showInGame(author, content);
        }
    }

    private static void showInGame(JsonObject author, String content) {
        String who = author.has("global_name") && !author.get("global_name").isJsonNull()
                ? author.get("global_name").getAsString() : author.get("username").getAsString();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Discord] " + who + ": " + content), false);
        });
    }
}
