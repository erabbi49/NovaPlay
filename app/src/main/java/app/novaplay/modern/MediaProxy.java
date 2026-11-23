package app.novaplay.modern;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fetches the site's video files the way the site's own player does.
 *
 * The video servers only hand a file to requests that look like they come from the site's own pages; anything
 * else gets a short "this video is only available at www.eporner.com" clip instead. A page that is loaded from the
 * app's assets sends no Referer at all, so the WebView's own request for the video is refused. For those requests
 * the WebView asks {@link #fetch}: it repeats the request natively with the site's Referer and the same browser
 * identity the page used, without the markers a WebView adds, and hands the bytes back, partial (Range) answers
 * included so seeking and buffering keep working.
 */
final class MediaProxy {
    /** What a visitor on the site's own pages sends as Referer. */
    static final String SITE = "https://www.eporner.com/";

    private static final String[] SITE_DOMAINS = {"eporner.com", "epcdn.com"};
    private static final String[] MEDIA_EXT = {".mp4", ".m4v", ".mov", ".webm", ".m3u8", ".ts", ".m4s", ".mpd"};

    /** Source of the cookies the WebView holds for a URL. */
    interface Cookies {
        String get(String url);
    }

    /** What the WebView needs to answer one request. */
    static final class Reply {
        final int status;
        final String reason;
        final String mime;
        final Map<String, String> headers;
        final InputStream body;

        Reply(int status, String reason, String mime, Map<String, String> headers, InputStream body) {
            this.status = status;
            this.reason = reason;
            this.mime = mime;
            this.headers = headers;
            this.body = body;
        }
    }

    private MediaProxy() {
    }

    /**
     * True for a request that is a video/audio fetch on one of the site's servers (or on a host the page said it is
     * about to play from). Pages, API calls and pictures are left to the WebView.
     */
    static boolean wants(String host, String path, Map<String, String> headers, Set<String> extraDomains) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        boolean ours = false;
        for (String d : SITE_DOMAINS) {
            if (isDomain(h, d)) ours = true;
        }
        if (!ours && extraDomains != null && inSet(h, extraDomains)) ours = true;
        if (!ours) return false;
        if (header(headers, "Range") != null) return true;
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        for (String ext : MEDIA_EXT) {
            if (p.endsWith(ext)) return true;
        }
        return false;
    }

    /** The part of a host name that identifies the server farm: "vid-3.cdn.example.net" gives "example.net". */
    static String registrable(String host) {
        if (host == null) return null;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.indexOf(':') >= 0 || h.matches("[0-9.]+")) return h;
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    /**
     * Repeats [url] with the site's Referer and returns what the server answered, or null when it could not be
     * fetched (the WebView then makes the request itself).
     */
    static Reply fetch(String url, Map<String, String> in, String ua, Cookies cookies) {
        try {
            String cur = url;
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = open(cur, in, ua, cookies);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isEmpty()) return null;
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                return reply(c, code, cur);
            }
        } catch (Exception ignored) {
            // fall back to the WebView's own request
        }
        return null;
    }

    private static HttpURLConnection open(String url, Map<String, String> in, String ua, Cookies cookies) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestMethod("GET");
        if (in != null) {
            for (Map.Entry<String, String> e : in.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null || skipRequest(k)) continue;
                c.setRequestProperty(k, v);
            }
        }
        if (ua != null && !ua.isEmpty()) c.setRequestProperty("User-Agent", ua);
        String ref = header(in, "Referer");
        c.setRequestProperty("Referer", ref != null && isSiteUrl(ref) ? ref : SITE);
        c.setRequestProperty("Accept-Encoding", "identity");
        String ck = cookies == null ? null : cookies.get(url);
        if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        return c;
    }

    private static Reply reply(HttpURLConnection c, int code, String url) throws IOException {
        if (code < 100 || code > 599 || (code >= 300 && code < 400)) {
            c.disconnect();
            return null;
        }
        InputStream raw = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (raw == null) raw = new ByteArrayInputStream(new byte[0]);
        Map<String, String> h = new HashMap<String, String>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            String k = e.getKey();
            List<String> vs = e.getValue();
            if (k == null || vs == null || vs.isEmpty() || dropResponse(k)) continue;
            StringBuilder sb = new StringBuilder();
            for (String v : vs) {
                if (v == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(v);
            }
            h.put(k, sb.toString());
        }
        return new Reply(code, reasonOf(code, c.getResponseMessage()), mimeOf(c.getContentType(), url), h, new Closing(raw, c));
    }

    /** Closing the stream (the player seeked or left) cuts the connection instead of reading out the rest of the file. */
    private static final class Closing extends FilterInputStream {
        private final HttpURLConnection conn;

        Closing(InputStream in, HttpURLConnection conn) {
            super(in);
            this.conn = conn;
        }

        @Override
        public void close() throws IOException {
            try {
                conn.disconnect();
            } finally {
                try {
                    super.close();
                } catch (IOException ignored) {
                    // already cut
                }
            }
        }
    }

    /* ---------- small helpers ---------- */

    private static boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean inSet(String host, Set<String> set) {
        String x = host;
        while (true) {
            if (set.contains(x)) return true;
            int i = x.indexOf('.');
            if (i < 0) return false;
            x = x.substring(i + 1);
        }
    }

    private static boolean isSiteUrl(String ref) {
        try {
            String h = new URL(ref).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            for (String d : SITE_DOMAINS) {
                if (isDomain(h, d)) return true;
            }
        } catch (Exception ignored) {
            // not a URL
        }
        return false;
    }

    private static String header(Map<String, String> m, String name) {
        if (m == null) return null;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /** Request headers that are set here, belong to the connection itself, or would turn a ranged answer into a 304. */
    private static boolean skipRequest(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        return k.equals("host") || k.equals("connection") || k.equals("keep-alive") || k.equals("proxy-authorization")
                || k.equals("proxy-connection") || k.equals("te") || k.equals("trailer") || k.equals("transfer-encoding")
                || k.equals("upgrade") || k.equals("content-length") || k.equals("accept-encoding") || k.equals("user-agent")
                || k.equals("referer") || k.equals("cookie") || k.equals("x-requested-with") || k.equals("cache-control")
                || k.equals("pragma") || k.equals("if-none-match") || k.equals("if-modified-since") || k.equals("if-range")
                || k.equals("if-match") || k.equals("if-unmodified-since");
    }

    private static boolean dropResponse(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        return k.equals("transfer-encoding") || k.equals("connection") || k.equals("keep-alive") || k.equals("trailer")
                || k.equals("upgrade") || k.equals("content-type") || k.equals("set-cookie") || k.equals("set-cookie2")
                || k.startsWith("proxy-") || k.startsWith("x-android-") || k.startsWith("okhttp-");
    }

    private static String mimeOf(String contentType, String url) {
        if (contentType != null) {
            int i = contentType.indexOf(';');
            String m = (i < 0 ? contentType : contentType.substring(0, i)).trim().toLowerCase(Locale.ROOT);
            if (!m.isEmpty()) return m;
        }
        String p = url.toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".mp4") || p.endsWith(".m4v")) return "video/mp4";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".mov")) return "video/quicktime";
        if (p.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (p.endsWith(".ts")) return "video/mp2t";
        if (p.endsWith(".mpd")) return "application/dash+xml";
        return "application/octet-stream";
    }

    private static String reasonOf(int code, String given) {
        if (given != null) {
            String g = given.trim();
            boolean clean = !g.isEmpty();
            for (int i = 0; i < g.length() && clean; i++) {
                if (g.charAt(i) < 32 || g.charAt(i) == 127) clean = false;
            }
            if (clean) return g;
        }
        switch (code) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 416:
                return "Range Not Satisfiable";
            default:
                return code >= 500 ? "Server Error" : (code >= 400 ? "Client Error" : "OK");
        }
    }
}
