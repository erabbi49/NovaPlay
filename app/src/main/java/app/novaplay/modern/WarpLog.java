package app.novaplay.modern;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * A short rolling log of what the built-in WARP connection did (Settings shows it, so a connection that fails can be
 * diagnosed from a copy of the text). Statuses, HTTP codes, Cloudflare's own addresses and ports are written; keys and
 * account tokens never are.
 */
final class WarpLog {
    private static final int MAX_LINES = 80;
    private static final int MAX_LEN = 220;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private WarpLog() {
    }

    static void add(String line) {
        if (line == null) return;
        StringBuilder sb = new StringBuilder(Math.min(line.length(), MAX_LEN) + 12);
        for (int i = 0; i < line.length() && sb.length() < MAX_LEN; i++) {
            char c = line.charAt(i);
            sb.append(c < 32 || c == 127 ? ' ' : c);
        }
        synchronized (LINES) {
            LINES.addLast(CLOCK.format(new Date()) + "  " + sb);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        }
    }

    /** The log, oldest line first. Empty when nothing happened yet. */
    static String dump() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            for (String s : LINES) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
