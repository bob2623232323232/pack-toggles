package dev.packtoggles;

import com.google.gson.JsonArray;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the free Cloudflare site (see capesite/worker.js).
 * siteUrl = e.g. https://trimstudio-site.yourname.workers.dev   siteKey = the SITE_KEY secret.
 * Set these (and chatToSite) from your settings screen / Config, then ChatBridge sends chat every 5 minutes.
 */
final class SiteClient {
    static volatile String siteUrl = "", siteKey = "";
    static volatile boolean chatToSite = false;
    static volatile String siteMsg = "";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private SiteClient() { }

    static boolean ready() { return !siteUrl.isBlank() && !siteKey.isBlank(); }

    private static String base() {
        String u = siteUrl.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** Called by ChatBridge on its own thread. Throws on failure so the message shows in siteMsg. */
    static void postChat(JsonArray lines) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base() + "/api/chat"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("x-key", siteKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(lines.toString())).build();
        HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) throw new Exception("wrong site key");
        if (res.statusCode() / 100 != 2) throw new Exception("HTTP " + res.statusCode());
    }
}
