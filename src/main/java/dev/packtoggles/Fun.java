package dev.packtoggles;

import java.util.Locale;
import java.util.Random;

/**
 * The "Fun" page of the Discord tab: little games you can run from Discord (/fit, /roll, /flip, /8ball, /rps)
 * or with the buttons in the game. Everything returns Discord-formatted text; the screen strips the markdown.
 */
final class Fun {
    /** Turned off on the Fun page: the Discord commands then politely refuse. */
    static volatile boolean on = true;

    /** The in-game 8-ball button has no text box, so it asks this. */
    static final String EIGHT_BALL_PROMPT = "Will I win my next fight?";

    private static final Random R = new Random();
    private static final int[] ARMOR_COL = {0xA06540, 0x9A9A9A, 0xD8D8D8, 0xF5CF3D, 0x4AEDD9, 0x4D494D};
    private static final String[] RARE = {"Silence", "Spire", "Flow", "Bolt", "Host", "Eye", "Raiser"};
    private static final String[] BALL = {
            "It is certain.", "Without a doubt.", "Yes, definitely.", "You may rely on it.", "As I see it, yes.",
            "Most likely.", "Outlook good.", "Signs point to yes.", "Reply hazy, try again.", "Ask again later.",
            "Better not tell you now.", "Cannot predict now.", "Concentrate and ask again.", "Don't count on it.",
            "My reply is no.", "My sources say no.", "Outlook not so good.", "Very doubtful."};

    private static String last = "Nothing yet. Press a button, or use /fit /roll /flip /8ball /rps in Discord";
    private static int fits = 0, flips = 0, rolls = 0, asks = 0, rpsW = 0, rpsL = 0, rpsD = 0;
    private static double bestFit = 0;
    private static String bestFitText = "-";
    private static int[] lastFit = null;

    private Fun() { }

    // ---------------- read by the screen ----------------
    static synchronized String last() { return last; }

    static synchronized int[] lastFit() { return lastFit == null ? null : lastFit.clone(); }

    static synchronized String[] stats() {
        return new String[]{
                "Fits rated: " + fits + "   best: " + (fits == 0 ? "-" : String.format(Locale.ROOT, "%.1f", bestFit)),
                bestFitText,
                "Coin flips: " + flips + "   dice rolls: " + rolls + "   8-ball: " + asks,
                "Rock paper scissors: " + rpsW + " won, " + rpsL + " lost, " + rpsD + " drawn"};
    }

    /** Plain text for the in-game panel. */
    static String plain(String s) { return s == null ? "" : s.replace("**", "").replace("`", ""); }

    private static synchronized String remember(String s) { last = s; return s; }

    // ---------------- fit roulette ----------------
    private static double lum(int c) { return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 255000.0; }

    private static double dist(int a, int b) {
        int dr = (a >> 16 & 255) - (b >> 16 & 255), dg = (a >> 8 & 255) - (b >> 8 & 255), db = (a & 255) - (b & 255);
        return Math.sqrt(dr * dr + dg * dg + db * db) / 441.7;
    }

    /** Armor and trim that clash look great, a trim the same colour as the armor vanishes. */
    static double rate(int armor, int mat, int pat) {
        int m = ToggleScreen.MAT_COL[mat] & 0xFFFFFF;
        double contrast = 0.6 * Math.abs(lum(ARMOR_COL[armor]) - lum(m)) + 0.4 * dist(ARMOR_COL[armor], m);
        double s = 2.5 + contrast * 8.2;
        for (String r : RARE) if (r.equals(ToggleScreen.PATTERNS[pat])) { s += r.equals("Silence") ? 1.4 : 0.8; break; }
        s += (R.nextDouble() - 0.5) * 1.2;
        return Math.round(Math.max(1.0, Math.min(10.0, s)) * 10.0) / 10.0;
    }

    private static String verdict(double s) {
        if (s < 3) return "where did the trim go?";
        if (s < 5) return "mid";
        if (s < 6.5) return "decent";
        if (s < 8) return "clean";
        if (s < 9.2) return "drippy";
        return "hall of fame";
    }

    static synchronized String fit() {
        int a = R.nextInt(ToggleScreen.ARMOR_NAMES.length), m = R.nextInt(ToggleScreen.MATS.length), p = R.nextInt(ToggleScreen.PATTERNS.length);
        double s = rate(a, m, p);
        fits++;
        lastFit = new int[]{a, m, p};
        String name = ToggleScreen.ARMOR_NAMES[a] + " armor, " + ToggleScreen.PATTERNS[p] + " trim, " + ToggleScreen.MATS[m];
        if (s > bestFit) { bestFit = s; bestFitText = "Best: " + name; }
        return last = "**" + ToggleScreen.ARMOR_NAMES[a] + "** armor with the **" + ToggleScreen.PATTERNS[p] + "** trim in **" + ToggleScreen.MATS[m]
                + "**\nFit score: **" + String.format(Locale.ROOT, "%.1f", s) + "/10** (" + verdict(s) + ")\n`" + ToggleScreen.giveCommandFor(a, m, p) + "`";
    }

    // ---------------- coin, dice, 8-ball ----------------
    static synchronized String flip() {
        flips++;
        int x = R.nextInt(100);
        return last = x == 0 ? "The coin landed on its **edge**. That never happens." : "The coin landed on **" + (x < 51 ? "heads" : "tails") + "**";
    }

    static synchronized String roll(int sides, int dice) {
        sides = Math.max(2, Math.min(1000, sides));
        dice = Math.max(1, Math.min(10, dice));
        rolls++;
        StringBuilder sb = new StringBuilder();
        int total = 0, first = 0;
        for (int i = 0; i < dice; i++) {
            int v = 1 + R.nextInt(sides);
            if (i == 0) first = v;
            total += v;
            if (i > 0) sb.append(" + ");
            sb.append(v);
        }
        String out = "Rolled " + dice + "d" + sides + (dice > 1 ? ": " + sb + " = **" + total + "**" : ": **" + total + "**");
        if (dice == 1 && sides == 20) out += first == 20 ? "  Natural 20!" : first == 1 ? "  Critical fail." : "";
        return last = out;
    }

    static synchronized String eightBall(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return last = "Ask a question first";
        asks++;
        if (q.length() > 120) q = q.substring(0, 120) + "..";
        return last = "You asked: " + q + "\n**" + BALL[R.nextInt(BALL.length)] + "**";
    }

    // ---------------- rock paper scissors ----------------
    private static final String[] RPS = {"rock", "paper", "scissors"};

    static synchronized String rps(String choice) {
        String c = choice == null ? "" : choice.trim().toLowerCase(Locale.ROOT);
        int me = -1;
        for (int i = 0; i < 3; i++) if (RPS[i].equals(c) || c.equals(RPS[i].substring(0, 1))) me = i;
        if (me < 0) return last = "Pick rock, paper or scissors";
        int bot = R.nextInt(3);
        String res;
        if (me == bot) { rpsD++; res = "Draw"; }
        else if ((me + 1) % 3 == bot) { rpsL++; res = "I win"; }
        else { rpsW++; res = "You win"; }
        return last = "You: **" + RPS[me] + "**   Me: **" + RPS[bot] + "**\n**" + res + "**";
    }
}
