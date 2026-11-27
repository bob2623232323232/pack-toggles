package dev.packtoggles;

import com.google.gson.JsonArray;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Talks to the free Cloudflare site (capesite/worker.js). No password. Address is editable on the Website tab. */
final class SiteClient {
    static volatile String siteUrl = "https://trimstudio-site.sv-community-cloudflare.workers.dev";
    static volatile boolean chatToSite = false;
    static volatile String siteMsg = "";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private SiteClient() { }

    static boolean ready() { return !siteUrl.isBlank(); }

    private static String base() {
        String u = siteUrl.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** Link to the site, optionally to a tab ("gallery", "chat"). */
    static String link(String hash) { return base() + "/" + (hash.isEmpty() ? "" : "#" + hash); }

    static void postChat(JsonArray lines) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base() + "/api/chat"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(lines.toString())).build();
        HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new Exception("HTTP " + res.statusCode());
    }
}
