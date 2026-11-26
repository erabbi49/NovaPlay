package app.novaplay.modern;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own small video server, reachable only on 127.0.0.1.
 *
 * The player page points its &lt;video&gt; at {@code http://127.0.0.1:port/v/token} instead of at the site's video
 * server. When the player asks for a file (or for a part of it: playing starts with "bytes=0-", and every seek asks
 * for "bytes=N-"), this server repeats the request to the site's server with the site's headers (see
 * {@link MediaProxy}) and streams the answer back unchanged. The player then talks plain HTTP to a plain HTTP
 * server, which is the one thing its media code is built around: seeking, buffering ahead and resuming all work the
 * way they do on any web page. Answering such requests from inside the WebView's request interception instead makes
 * the WebView apply the Range header to the stream a second time, and a seek stalls.
 *
 * Only addresses the page registered are served, each under a random token, and only to requests whose Host is the
 * loopback address and this server's port.
 */
final class LocalMediaServer {
    static final String LOOPBACK = "127.0.0.1";

    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_LINKS = 300;
    private static final int CHUNK = 64 * 1024;
    private static final Pattern PATH = Pattern.compile("^/v/([0-9a-f]{32})(?:\\.[a-z0-9]{2,5})?$");
    /** One byte range: "bytes=500-999", "bytes=500-" or "bytes=-500". Several ranges in one request are not served. */
    private static final Pattern RANGE = Pattern.compile("^bytes=(?:\\d+-\\d*|-\\d+)$");
    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes (\\d+)-(\\d+)/(?:\\d+|\\*)$");
    /** How many times one answer may be continued after the site's server stopped it short. */
    private static final int MAX_RESUMES = 8;
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final String[] VIDEO_EXT = {".mp4", ".m4v", ".webm", ".mov"};

    private final String ua;
    private final MediaProxy.Cookies cookies;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private final LinkedHashMap<String, String> urlByToken = new LinkedHashMap<String, String>();
    private final HashMap<String, String> tokenByUrl = new HashMap<String, String>();
    private final Set<Conn> open = new HashSet<Conn>();
    private final AtomicInteger seq = new AtomicInteger();
    private ServerSocket server;
    private ExecutorService pool;
    private volatile int port;
    private boolean stopped;
    /** How long a player may take to send its request before the connection is dropped. */
    volatile int headTimeoutMs = 10000;

    /** Where the site's answers come from. Tests put their own here; the app always uses {@link MediaProxy#fetch}. */
    interface Fetcher {
        MediaProxy.Reply fetch(String url, Map<String, String> headers);
    }

    volatile Fetcher fetcher;

    LocalMediaServer(String ua, MediaProxy.Cookies cookies) {
        this.ua = ua;
        this.cookies = cookies;
    }

    private MediaProxy.Reply fetch(String url, Map<String, String> headers) {
        Fetcher f = fetcher;
        return f != null ? f.fetch(url, headers) : MediaProxy.fetch(url, headers, ua, cookies);
    }

    /** What went wrong, without addresses: the exception's type and where it came from. */
    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 2; i++) {
            sb.append(i == 0 ? " at " : " < ").append(st[i].getClassName().replaceFirst("^.*\\.", ""))
                    .append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    /** True for the server's own address: requests to it are the network stack's business, not the shell's. */
    static boolean isLoopback(String host) {
        return host != null && (host.equals(LOOPBACK) || host.equalsIgnoreCase("localhost"));
    }

    int port() {
        return port;
    }

    /**
     * The address the player should load [url] from, or null when the server could not be started (the player then
     * uses [url] itself). Asking again for the same [url] gives the same address.
     */
    String register(String url) {
        if (url == null || url.length() > 4096
                || !(url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8))) {
            return null;
        }
        synchronized (lock) {
            if (stopped || !running()) return null;
            String token = tokenByUrl.get(url);
            if (token == null) {
                token = newToken();
                urlByToken.put(token, url);
                tokenByUrl.put(url, token);
                while (urlByToken.size() > MAX_LINKS) {
                    Iterator<Map.Entry<String, String>> it = urlByToken.entrySet().iterator();
                    Map.Entry<String, String> oldest = it.next();
                    it.remove();
                    tokenByUrl.remove(oldest.getValue());
                }
            }
            return "http://" + LOOPBACK + ":" + port + "/v/" + token + extensionOf(url);
        }
    }

    /** Closes the server and every connection it still has open. Registered addresses are forgotten. */
    void stop() {
        ServerSocket s;
        ExecutorService p;
        Set<Conn> conns;
        synchronized (lock) {
            stopped = true;
            s = server;
            p = pool;
            server = null;
            pool = null;
            conns = new HashSet<Conn>(open);
            open.clear();
            urlByToken.clear();
            tokenByUrl.clear();
        }
        closeQuietly(s);
        if (p != null) p.shutdownNow();
        for (Conn c : conns) c.cancel();
    }

    /* ---------- starting ---------- */

    /** Starts the server unless it is running. Called with {@link #lock} held. */
    private boolean running() {
        if (server != null && !server.isClosed()) return true;
        try {
            final ServerSocket s = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            final ExecutorService p = Executors.newCachedThreadPool(new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "nova-media-" + n.incrementAndGet());
                    t.setDaemon(true);
                    // whatever slips through ends one request, never the app: the player simply asks again
                    t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(Thread th, Throwable e) {
                            MediaLog.add("local server thread error: " + describe(e));
                        }
                    });
                    return t;
                }
            });
            server = s;
            pool = p;
            port = s.getLocalPort();
            p.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        accept(s, p);
                    } catch (Throwable e) {
                        MediaLog.add("local server accept error: " + describe(e));
                    }
                }
            });
            MediaLog.add("local video server ready on port " + port);
            return true;
        } catch (IOException e) {
            MediaLog.add("local video server could not start: " + e.getMessage());
            return false;
        }
    }

    private void accept(ServerSocket s, final ExecutorService p) {
        while (!s.isClosed()) {
            final Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                if (s.isClosed()) return;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            try {
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(c, p);
                    }
                });
            } catch (RuntimeException e) {
                closeQuietly(c); // the server was stopped while a player was connecting
                return;
            }
        }
    }

    /* ---------- one connection ---------- */

    /**
     * One player connection: the socket, the answer coming from the site's server, and whether the player left.
     *
     * Two threads work on a connection: the one that serves it (reads the site's answer, writes it to the player)
     * and a watcher that notices the player hanging up. The HTTP stack's streams must never be closed while another
     * thread reads them (the platform's HTTP client says so itself: it throws unchecked exceptions from inside the
     * read, and from the close). So the watcher only ever <em>cuts</em> the connections ({@link #cancel}); the
     * serving thread alone releases the stream ({@link #close}).
     */
    private static final class Conn {
        final Socket sock;
        volatile MediaProxy.Reply upstream;
        volatile boolean cancelled;
        volatile boolean finished;

        Conn(Socket sock) {
            this.sock = sock;
        }

        /**
         * Waits for the player to close its end (it does when it seeks or leaves) and then cuts the connection to the
         * site's server, so nothing keeps downloading a part of the file nobody is going to watch.
         */
        void watch(ExecutorService p) {
            try {
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            InputStream in = sock.getInputStream();
                            byte[] junk = new byte[256];
                            while (in.read(junk) >= 0) {
                                // a player sends nothing more after its request
                            }
                        } catch (IOException ignored) {
                            // closed by either side
                        } catch (Throwable e) {
                            MediaLog.add("local server watcher error: " + describe(e));
                        }
                        if (!finished) cancel();
                    }
                });
            } catch (RuntimeException ignored) {
                // the server was stopped
            }
        }

        /** From any thread: stop the transfer. Cuts the connections; the serving thread notices and cleans up. */
        void cancel() {
            cancelled = true;
            try {
                MediaProxy.Reply r = upstream;
                if (r != null) r.abort();
            } catch (Throwable ignored) {
                // nothing more can be done about a connection that is already gone
            }
            closeQuietly(sock);
        }

        /** Only from the thread that serves this connection: releases the site's stream, then the player's socket. */
        void close() {
            finished = true;
            try {
                MediaProxy.Reply r = upstream;
                if (r != null) {
                    r.abort();
                    closeQuietly(r.body);
                }
            } catch (Throwable ignored) {
                // the stream is released as far as it can be
            }
            closeQuietly(sock);
        }
    }

    private void serve(Socket sock, ExecutorService p) {
        Conn conn = new Conn(sock);
        try {
            synchronized (lock) {
                if (stopped) {
                    conn.close();
                    return;
                }
                open.add(conn);
            }
            handle(conn, p);
        } catch (IOException ignored) {
            // the player went away or never sent a usable request
        } catch (Throwable e) {
            MediaLog.add("local server error: " + describe(e));
        } finally {
            try {
                conn.close();
            } finally {
                synchronized (lock) {
                    open.remove(conn);
                }
            }
        }
    }

    private void handle(Conn conn, ExecutorService p) throws IOException {
        Socket sock = conn.sock;
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(headTimeoutMs);
        String text = readHead(sock.getInputStream());
        if (text == null) return;
        sock.setSoTimeout(0);
        OutputStream out = sock.getOutputStream();

        Request rq = parse(text);
        if (rq == null) {
            reply(out, 400, "Bad Request", "Bad request.", "");
            return;
        }
        if (!hostOk(rq.headers.get("host"))) {
            MediaLog.add("refused a request that was not meant for this server");
            reply(out, 403, "Forbidden", "Forbidden.", "");
            return;
        }
        if ("OPTIONS".equals(rq.method)) {
            reply(out, 204, "No Content", null, "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: Range, Accept, Accept-Language\r\nAccess-Control-Max-Age: 600\r\n");
            return;
        }
        boolean headOnly = "HEAD".equals(rq.method);
        if (!headOnly && !"GET".equals(rq.method)) {
            reply(out, 405, "Method Not Allowed", "Method not allowed.", "Allow: GET, HEAD, OPTIONS\r\n");
            return;
        }
        String path = rq.target;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        Matcher m = PATH.matcher(path);
        String url = m.matches() ? urlFor(m.group(1)) : null;
        if (url == null) {
            reply(out, 404, "Not Found", "Not found.", "");
            return;
        }

        // what is passed on: one well-formed byte range, and the harmless negotiation headers
        Map<String, String> fwd = new HashMap<String, String>();
        String range = rq.headers.get("range");
        String rangeText = "all";
        if (range != null && RANGE.matcher(range.trim()).matches()) {
            fwd.put("Range", range.trim());
            rangeText = range.trim().substring(6);
        }
        String accept = rq.headers.get("accept");
        if (accept != null) fwd.put("Accept", accept);
        String lang = rq.headers.get("accept-language");
        if (lang != null) fwd.put("Accept-Language", lang);

        int id = seq.incrementAndGet();
        String host = MediaLog.host(url);
        long t0 = System.currentTimeMillis();
        conn.watch(p);
        MediaProxy.Reply r = fetch(url, fwd);
        if (r != null) conn.upstream = r; // from here on the watcher can cut it
        if (conn.cancelled) {
            // the player left while the answer was on its way; Conn.close() releases the stream
            MediaLog.add("#" + id + " " + host + " " + rangeText + ": player moved on before the answer");
            return;
        }
        if (r == null) {
            MediaLog.add("#" + id + " " + host + " " + rangeText + ": no answer from the video server");
            reply(out, 502, "Bad Gateway", "The video server did not answer.", "");
            return;
        }
        String cr = header(r.headers, "Content-Range");
        String cl = header(r.headers, "Content-Length");
        MediaLog.add("#" + id + " " + host + " " + rangeText + " -> " + r.status
                + (cr != null ? " [" + cr + "]" : (cl != null ? " [length " + cl + "]" : "")));
        out.write(responseHead(r));
        if (headOnly) {
            out.flush();
            return;
        }

        // where this answer sits in the file, so a stream that stops early can be continued where it stopped
        long pos = 0;
        long end = -1;
        long[] span = contentRange(cr);
        if (r.status == 206 && span != null) {
            pos = span[0];
            end = span[1];
        } else if (r.status == 200 && cl != null) {
            end = parseLong(cl) - 1;
        }
        long sent = 0;
        int resumes = 0;
        String why = "complete";
        byte[] buf = new byte[CHUNK];
        InputStream body = r.body;
        while (true) {
            int n;
            String err = null;
            try {
                n = body.read(buf);
            } catch (IOException e) {
                n = -1;
                err = e.getClass().getSimpleName();
            } catch (RuntimeException e) {
                // the HTTP stack throws unchecked exceptions when its connection is cut under a read
                n = -1;
                err = e.getClass().getSimpleName();
            }
            if (n > 0) {
                try {
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    why = "player moved on";
                    break;
                }
                sent += n;
                pos += n;
                continue;
            }
            if (conn.cancelled) { // the watcher cut the stream: the player left
                why = "player moved on";
                break;
            }
            // the stream ended or failed; with a known length it is only complete when every byte arrived
            if (end >= 0 ? pos > end : err == null) break;
            if (end >= 0 && resumes < MAX_RESUMES) {
                // the site's server dropped the connection (idle while the player was paused, a busy server...):
                // ask for the rest and carry on, so the player never sees a response that stops short
                resumes++;
                MediaLog.add("#" + id + " the video server stopped at byte " + pos + "; asking for the rest");
                if (resumes > 1) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        why = "interrupted";
                        break;
                    }
                }
                MediaProxy.Reply next = resume(url, pos, end);
                if (next != null) {
                    InputStream old = body;
                    conn.upstream = next; // the watcher cuts the new connection from now on...
                    closeQuietly(old);    // ...and only this thread ever closes a stream
                    body = next.body;
                    if (conn.cancelled) {
                        why = "player moved on";
                        break;
                    }
                    continue;
                }
            }
            why = err != null ? "video server error (" + err + ")" : "video server closed early";
            break;
        }
        try {
            out.flush();
        } catch (IOException ignored) {
            // the player is gone; nothing left to send
        }
        long ms = System.currentTimeMillis() - t0;
        MediaLog.add("#" + id + " sent " + MediaLog.size(sent) + " in " + (ms / 100) / 10.0 + " s: " + why
                + (resumes > 0 ? " (continued " + resumes + "x)" : ""));
    }

    /** Asks the site's server for bytes [pos]..[end] of [url]; null unless it answers with exactly that part. */
    private MediaProxy.Reply resume(String url, long pos, long end) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Range", "bytes=" + pos + "-" + end);
        MediaProxy.Reply next = fetch(url, h);
        if (next == null) return null;
        long[] span = next.status == 206 ? contentRange(header(next.headers, "Content-Range")) : null;
        if (span == null || span[0] != pos) {
            next.abort();
            closeQuietly(next.body);
            return null;
        }
        return next;
    }

    /** First and last byte of a "bytes 100-199/1000" Content-Range, or null. */
    private static long[] contentRange(String v) {
        if (v == null) return null;
        Matcher m = CONTENT_RANGE.matcher(v.trim());
        if (!m.matches()) return null;
        try {
            long a = Long.parseLong(m.group(1));
            long b = Long.parseLong(m.group(2));
            return b >= a ? new long[]{a, b} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String urlFor(String token) {
        synchronized (lock) {
            return urlByToken.get(token);
        }
    }

    /** Only requests addressed to this server by its own address count; a page tricked onto it sends another Host. */
    private boolean hostOk(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        int p = port;
        return h.equals(LOOPBACK + ":" + p) || h.equals("localhost:" + p);
    }

    /* ---------- reading the request ---------- */

    private static final class Request {
        String method = "";
        String target = "";
        final Map<String, String> headers = new HashMap<String, String>(); // names in lower case
    }

    /** The request line and headers up to the blank line, or null when the player closed before sending them. */
    private static String readHead(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_HEAD];
        int len = 0;
        while (len < buf.length) {
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) return null;
            int from = Math.max(0, len - 3);
            len += n;
            for (int i = from; i + 3 < len; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                    return new String(buf, 0, i, StandardCharsets.ISO_8859_1);
                }
            }
        }
        throw new IOException("request head too large");
    }

    private static Request parse(String head) {
        String[] lines = head.split("\r\n", -1);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[2].startsWith("HTTP/1.") || first[0].isEmpty() || first[1].isEmpty()) return null;
        Request r = new Request();
        r.method = first[0];
        r.target = first[1];
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        return r;
    }

    /* ---------- writing the answer ---------- */

    /** A short answer of the server's own (an error, or the reply to a CORS check). */
    private static void reply(OutputStream out, int code, String reason, String text, String extra) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        byte[] body = new byte[0];
        if (text != null && code != 204) {
            body = text.getBytes(StandardCharsets.UTF_8);
            sb.append("Content-Type: text/plain; charset=utf-8\r\nContent-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Access-Control-Allow-Origin: *\r\n").append(extra).append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    /** The status line and headers the site's server answered with, minus what only belongs to that connection. */
    private static byte[] responseHead(MediaProxy.Reply r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(valueOk(r.reason) ? r.reason : "OK").append("\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null || !TOKEN.matcher(k).matches() || !valueOk(v) || ours(k)) continue;
            sb.append(k).append(": ").append(v).append("\r\n");
        }
        if (r.mime != null && valueOk(r.mime)) sb.append("Content-Type: ").append(r.mime).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Headers this server writes itself, or that describe the connection to the site's server, not this one. */
    private static boolean ours(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        return k.equals("content-type") || k.equals("connection") || k.equals("keep-alive") || k.equals("transfer-encoding")
                || k.equals("trailer") || k.equals("upgrade") || k.startsWith("proxy-") || k.startsWith("access-control-");
    }

    private static boolean valueOk(String v) {
        if (v == null || v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c < 32 && c != '\t') || c == 127) return false;
        }
        return true;
    }

    private static String header(Map<String, String> m, String name) {
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /* ---------- small helpers ---------- */

    private String newToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    /** ".mp4" for an address that ends in a video file name: some players pick the container from the name. */
    private static String extensionOf(String url) {
        int end = url.length();
        int q = url.indexOf('?');
        if (q >= 0) end = q;
        int h = url.indexOf('#');
        if (h >= 0 && h < end) end = h;
        String p = url.substring(0, end).toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXT) {
            if (p.endsWith(ext)) return ext;
        }
        return "";
    }

    /** Closes [c], whatever it throws: streams of the platform's HTTP client throw unchecked exceptions when cut. */
    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // already closed
        }
    }
}
