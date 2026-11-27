package dev.packtoggles;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Simple Voice Chat support, done by reflection so the mod still works without it.
 * Simple Voice Chat only tells other clients whether a player has voice chat off/deafened,
 * is not connected, or is talking. Whether someone muted only their mic is not shared by the
 * voice chat server, so it can't be shown.
 */
final class VoiceStatus {
    enum State {
        UNKNOWN(""), NO_MOD("no voice"), NOT_CONNECTED("no voice"), DISABLED("deafened"), CONNECTED("voice"), TALKING("talking");
        final String label;
        State(String l) { label = l; }
    }

    private static Boolean present;
    private static long nextTry = 0;

    private VoiceStatus() { }

    static boolean available() {
        if (present == null) {
            try { Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager"); present = true; }
            catch (Throwable t) { present = false; }
        }
        return present;
    }

    static State of(UUID id) {
        if (!available()) return State.UNKNOWN;
        if (System.currentTimeMillis() < nextTry) return State.UNKNOWN;
        try {
            Class<?> cm = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
            Object psm = cm.getMethod("getPlayerStateManager").invoke(null);
            if (psm == null) return State.UNKNOWN;
            Object st = call(psm, "getState", id);
            if (st == null) return State.NOT_CONNECTED;
            if (Boolean.TRUE.equals(call(st, "isDisconnected"))) return State.NOT_CONNECTED;
            if (Boolean.TRUE.equals(call(st, "isDisabled"))) return State.DISABLED;
            Object client = cm.getMethod("getClient").invoke(null);
            if (client != null) {
                Object talk = call(client, "getTalkCache");
                if (talk != null && Boolean.TRUE.equals(call(talk, "isTalking", id))) return State.TALKING;
            }
            return State.CONNECTED;
        } catch (Throwable t) {
            nextTry = System.currentTimeMillis() + 10000L;  // API changed: back off instead of spamming errors
            return State.UNKNOWN;
        }
    }

    private static Object call(Object o, String name, Object... args) {
        try {
            for (Method m : o.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                if (args.length == 1 && !m.getParameterTypes()[0].isInstance(args[0])) continue;
                return m.invoke(o, args);
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
