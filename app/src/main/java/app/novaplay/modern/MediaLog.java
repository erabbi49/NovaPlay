package app.novaplay.modern;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * A short rolling log of what the video connection did. Settings shows it, so a video that will not load can be
 * diagnosed from a screenshot. Only host names, byte ranges and counts are written, never a full address.
 */
final class MediaLog {
    private static final int MAX_LINES = 80;
    private static final int MAX_LEN = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private MediaLog() {
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

    /** The log, oldest line first, one line per event. Empty when nothing happened yet. */
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

    /** The host part of an address ("https://a.b.net:8080/x?y" gives "a.b.net"), or "?" when there is none. */
    static String host(String url) {
        if (url == null) return "?";
        int i = url.indexOf("://");
        if (i < 0) return "?";
        int s = i + 3, e = s;
        while (e < url.length() && "/?#".indexOf(url.charAt(e)) < 0) e++;
        String auth = url.substring(s, e);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        int colon = auth.lastIndexOf(':');
        if (colon > 0 && auth.indexOf(']') < 0) auth = auth.substring(0, colon);
        return auth.isEmpty() ? "?" : auth;
    }

    /** "812 B", "37 KB", "4.2 MB". */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
        double mb = bytes / 1048576.0;
        return (mb < 100 ? String.format(Locale.US, "%.1f", mb) : String.valueOf(Math.round(mb))) + " MB";
    }
}
