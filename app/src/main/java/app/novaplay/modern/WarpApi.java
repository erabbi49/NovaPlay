package app.novaplay.modern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

/**
 * Cloudflare WARP, the way its own 1.1.1.1 app starts: the phone makes up a WireGuard key, tells Cloudflare's API
 * about its public half, and gets back the address it may use inside the tunnel and the server to connect to.
 * (This is the same registration the open-source "wgcf" tool performs.)
 *
 * Nothing here can be tested against the real service from where the app is built, so every step writes a line to
 * {@link WarpLog} and every answer is checked before it is used.
 */
final class WarpApi {
    /** Where the API lives. Tests point this at a local fake. */
    static volatile String baseUrl = "https://api.cloudflareclient.com";
    /** Where the "is this really WARP?" page lives. Tests point this at a local fake. */
    static volatile String traceUrl = "https://1.1.1.1/cdn-cgi/trace";
    /** When the phone's own DNS does not know the API's name, ask a public DNS-over-HTTPS server instead. */
    static volatile boolean dohFallback = true;

    static final int PROBE_NONE = -1;
    static final int PROBE_DIRECT = 0;
    static final int PROBE_WARP = 1;

    private static final int MAX_BODY = 64 * 1024;
    /** Public DNS-over-HTTPS servers, reached by their own addresses (tests point this at a local fake). */
    static volatile String[] dohServers = {
            "https://1.1.1.1/dns-query?type=A&name=",
            "https://1.0.0.1/dns-query?type=A&name=",
            "https://8.8.8.8/resolve?type=A&name="
    };

    /** One way of introducing the phone to the API; the first that Cloudflare accepts is used. */
    private static final class Client {
        final String version;
        final String userAgent;
        final String cfVersion;
        final boolean modern;

        Client(String version, String userAgent, String cfVersion, boolean modern) {
            this.version = version;
            this.userAgent = userAgent;
            this.cfVersion = cfVersion;
            this.modern = modern;
        }
    }

    private static final Client[] CLIENTS = {
            new Client("v0a5641", "1.1.1.1/6.38.9-5641 (Android 16.0.0)", "a-6.38.9-5641", true),
            new Client("v0a2158", "okhttp/3.12.1", "a-6.3-2158", false)
    };

    /** What Cloudflare answered when the phone registered. */
    static final class Registration {
        final String id;
        final String token;
        final String peerKey;
        final String v4;
        final String v6;
        final String endpoint;
        final int[] ports;

        Registration(String id, String token, String peerKey, String v4, String v6, String endpoint, int[] ports) {
            this.id = id;
            this.token = token;
            this.peerKey = peerKey;
            this.v4 = v4;
            this.v6 = v6;
            this.endpoint = endpoint;
            this.ports = ports;
        }
    }

    private static final class Response {
        final int code;
        final String text;

        Response(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    private WarpApi() {
    }

    /* ---------- registering ---------- */

    /** Registers a phone whose WireGuard public key is [publicKey] (base64). */
    static Registration register(String publicKey) throws IOException {
        IOException last = null;
        StringBuilder why = new StringBuilder();
        for (Client c : CLIENTS) {
            WarpLog.add("registering with Cloudflare (" + c.version + ")");
            try {
                Registration r = registerWith(c, publicKey);
                WarpLog.add(c.version + ": registered; server " + r.endpoint + ", ports " + portsText(r.ports));
                return r;
            } catch (IOException e) {
                last = e;
                WarpLog.add(c.version + ": " + e.getMessage());
                if (why.length() > 0) why.append("; ");
                why.append(c.version).append(": ").append(e.getMessage());
            }
        }
        throw new IOException("Cloudflare did not accept the registration (" + why + ")", last);
    }

    private static Registration registerWith(Client c, String publicKey) throws IOException {
        String base = baseUrl + "/" + c.version + "/reg";
        Response r = send("POST", base, c, null, requestBody(c, publicKey));
        if (r.code != 200 && r.code != 201) throw new IOException("HTTP " + r.code + excerpt(r.text));
        JSONObject root = parseObject(r.text);
        Registration reg = fromAnswer(root, null, null);
        if (reg != null) return reg;
        // some answers hold the account only; its settings are one more call away
        String id = root.optString("id", "");
        String token = root.optString("token", "");
        if (WarpAccount.validId(id) && !id.isEmpty() && WarpAccount.validId(token) && !token.isEmpty()) {
            WarpLog.add(c.version + ": the answer had no settings; asking for them");
            Response g = send("GET", base + "/" + id, c, token, null);
            if (g.code != 200) throw new IOException("HTTP " + g.code + " when asking for the settings" + excerpt(g.text));
            reg = fromAnswer(parseObject(g.text), id, token);
            if (reg != null) return reg;
        }
        throw new IOException("the answer had no WireGuard settings");
    }

    private static String requestBody(Client c, String publicKey) throws IOException {
        try {
            JSONObject b = new JSONObject();
            b.put("key", publicKey);
            b.put("install_id", "");
            b.put("fcm_token", "");
            b.put("tos", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
            b.put("model", "PC");
            b.put("serial_number", "");
            b.put("locale", "en_US");
            if (c.modern) {
                b.put("os_version", "16.0.0");
                b.put("key_type", "curve25519");
                b.put("tunnel_type", "wireguard");
            } else {
                b.put("type", "Android");
            }
            return b.toString();
        } catch (JSONException e) {
            throw new IOException("could not build the request");
        }
    }

    private static JSONObject parseObject(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("the answer was not readable");
        }
    }

    /** The settings in an answer, or null when it holds none. Every value that ends up in a config is checked. */
    static Registration fromAnswer(JSONObject root, String fallbackId, String fallbackToken) throws IOException {
        String id = root.optString("id", fallbackId == null ? "" : fallbackId);
        String token = root.optString("token", fallbackToken == null ? "" : fallbackToken);
        JSONObject cfg = root.optJSONObject("config");
        if (cfg == null) return null;
        JSONArray peers = cfg.optJSONArray("peers");
        JSONObject peer = peers == null ? null : peers.optJSONObject(0);
        JSONObject iface = cfg.optJSONObject("interface");
        JSONObject addr = iface == null ? null : iface.optJSONObject("addresses");
        if (peer == null || addr == null) return null;

        String peerKey = peer.optString("public_key", "");
        if (!WarpAccount.validKey(peerKey)) throw new IOException("the server's key looked wrong");
        String v4 = addr.optString("v4", "");
        if (!WarpAccount.validV4(v4)) throw new IOException("the tunnel address looked wrong");
        String v6 = addr.optString("v6", "");
        if (!v6.isEmpty() && !WarpAccount.validV6(v6)) v6 = ""; // IPv4 alone works
        if (!WarpAccount.validId(id)) id = "";
        if (!WarpAccount.validId(token)) token = "";

        // where to connect: the server's IPv4 address (a name would need the phone's DNS before the tunnel is up, which
        // is the very thing that may be blocked), else the well-known address; the port is separate
        String endpoint = "";
        int hostPort = 0;
        JSONObject ep = peer.optJSONObject("endpoint");
        List<Integer> ports = new ArrayList<Integer>();
        if (ep != null) {
            String v4ep = ep.optString("v4", "");
            String host = ep.optString("host", "");
            String ip = stripPort(v4ep);
            if (WarpAccount.validV4(ip)) endpoint = ip;
            int p = portOf(host);
            if (p > 0) hostPort = p;
            JSONArray pa = ep.optJSONArray("ports");
            for (int i = 0; pa != null && i < pa.length(); i++) {
                int x = pa.optInt(i, 0);
                if (x > 0 && x < 65536 && !ports.contains(x)) ports.add(x);
            }
        }
        if (endpoint.isEmpty()) endpoint = WarpAccount.DEFAULT_ENDPOINT;
        // the port the API names first, then the usual ones, then whatever else it listed
        List<Integer> order = new ArrayList<Integer>();
        if (hostPort > 0) order.add(hostPort);
        for (int x : WarpAccount.DEFAULT_PORTS) {
            if (!order.contains(x)) order.add(x);
        }
        for (int x : ports) {
            if (!order.contains(x) && order.size() < 6) order.add(x);
        }
        int[] arr = new int[order.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = order.get(i);
        return new Registration(id, token, peerKey, v4, v6, endpoint, arr);
    }

    /** "162.159.192.1:0" gives "162.159.192.1"; "[2606::1]:0" gives "2606::1"; "host:2408" gives "host". */
    static String stripPort(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("[")) {
            int e = s.indexOf(']');
            return e > 0 ? s.substring(1, e) : "";
        }
        int c = s.lastIndexOf(':');
        if (c > 0 && s.indexOf(':') == c) return s.substring(0, c); // exactly one colon: host:port
        return s;
    }

    /** The port in "host:2408" / "[::1]:2408", or 0 when there is none. */
    static int portOf(String s) {
        if (s == null) return 0;
        s = s.trim();
        int c = s.lastIndexOf(':');
        if (c < 0 || (s.indexOf(':') != c && !s.startsWith("["))) return 0;
        try {
            int p = Integer.parseInt(s.substring(c + 1));
            return p > 0 && p < 65536 ? p : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String portsText(int[] ports) {
        StringBuilder sb = new StringBuilder();
        for (int p : ports) sb.append(sb.length() == 0 ? "" : ",").append(p);
        return sb.toString();
    }

    /* ---------- is the traffic really going through WARP? ---------- */

    /**
     * Asks Cloudflare's own "trace" page which way this request came: {@link #PROBE_WARP} when it says WARP is on,
     * {@link #PROBE_DIRECT} when the network works but not through WARP, {@link #PROBE_NONE} when nothing answered.
     * The first request also makes the tunnel do its handshake, so this is what finds out whether Cloudflare can be
     * reached over the network the phone is on.
     */
    static int probe() {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(traceUrl).openConnection();
            h.setConnectTimeout(6000);
            h.setReadTimeout(5000);
            h.setUseCaches(false);
            h.setInstanceFollowRedirects(false);
            int code = h.getResponseCode();
            String text = code == 200 ? read(h.getInputStream()) : "";
            if (text.contains("warp=on") || text.contains("warp=plus")) {
                WarpLog.add("check: Cloudflare says the traffic comes through WARP");
                return PROBE_WARP;
            }
            if (text.contains("warp=off")) {
                WarpLog.add("check: the network works, but Cloudflare says this traffic is not coming through WARP");
                return PROBE_DIRECT;
            }
            WarpLog.add("check: unexpected answer (HTTP " + code + ")");
            return PROBE_NONE;
        } catch (IOException e) {
            WarpLog.add("check: no answer (" + e.getClass().getSimpleName() + ")");
            return PROBE_NONE;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /* ---------- HTTP ---------- */

    private static Response send(String method, String url, Client c, String bearer, String json) throws IOException {
        try {
            return sendOnce(method, url, null, c, bearer, json);
        } catch (UnknownHostException e) {
            if (!dohFallback) throw e;
            String host = hostOf(url);
            String ip = resolveOverHttps(host);
            if (ip == null) throw new IOException("the phone's DNS does not know " + host + " and DNS-over-HTTPS did not help");
            WarpLog.add("the phone's DNS does not know " + host + "; using " + ip + " from DNS-over-HTTPS");
            return sendOnce(method, url, ip, c, bearer, json);
        }
    }

    private static Response sendOnce(String method, String url, String ip, Client c, String bearer, String json) throws IOException {
        final String host = hostOf(url);
        String target = url;
        if (ip != null) {
            URL u = new URL(url);
            target = u.getProtocol() + "://" + ip + (u.getPort() >= 0 ? ":" + u.getPort() : "") + u.getFile();
        }
        HttpURLConnection h = (HttpURLConnection) new URL(target).openConnection();
        try {
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            h.setUseCaches(false);
            h.setInstanceFollowRedirects(false);
            h.setRequestMethod(method);
            h.setRequestProperty("User-Agent", c.userAgent);
            h.setRequestProperty("CF-Client-Version", c.cfVersion);
            if (bearer != null) h.setRequestProperty("Authorization", "Bearer " + bearer);
            if (ip != null) {
                int up = new URL(url).getPort();
                h.setRequestProperty("Host", up >= 0 ? host + ":" + up : host);
                if (h instanceof HttpsURLConnection) {
                    HttpsURLConnection s = (HttpsURLConnection) h;
                    s.setSSLSocketFactory(new SniFactory(host));
                    s.setHostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
                        }
                    });
                }
            }
            if (json != null) {
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                h.setFixedLengthStreamingMode(b.length);
                OutputStream out = h.getOutputStream();
                try {
                    out.write(b);
                    out.flush();
                } finally {
                    out.close();
                }
            }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            return new Response(code, in == null ? "" : read(in));
        } finally {
            h.disconnect();
        }
    }

    /** An IPv4 address for [host] from a public DNS-over-HTTPS server (reached by its own address), or null. */
    static String resolveOverHttps(String host) {
        for (String base : dohServers) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(base + host).openConnection();
                h.setConnectTimeout(5000);
                h.setReadTimeout(5000);
                h.setUseCaches(false);
                h.setRequestProperty("Accept", "application/dns-json");
                if (h.getResponseCode() != 200) continue;
                JSONObject o = new JSONObject(read(h.getInputStream()));
                JSONArray a = o.optJSONArray("Answer");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    if (x != null && x.optInt("type", 0) == 1 && WarpAccount.validV4(x.optString("data", ""))) {
                        return x.optString("data", "");
                    }
                }
            } catch (IOException e) {
                // try the next server
            } catch (JSONException e) {
                // try the next server
            } finally {
                if (h != null) h.disconnect();
            }
        }
        return null;
    }

    /** Connects to an address but speaks to it as [sni]: the name the certificate is for. */
    private static final class SniFactory extends SSLSocketFactory {
        private final SSLSocketFactory d = (SSLSocketFactory) SSLSocketFactory.getDefault();
        private final String sni;

        SniFactory(String sni) {
            this.sni = sni;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return d.createSocket(s, sni, port, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return d.createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws IOException {
            return d.createSocket(host, port, local, localPort);
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return d.createSocket(host, port);
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws IOException {
            return d.createSocket(host, port, local, localPort);
        }
    }

    private static String hostOf(String url) throws IOException {
        return new URL(url).getHost();
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (bo.size() < MAX_BODY && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** " (first words of the answer)" for a log line, or "". */
    private static String excerpt(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "";
        return " (" + (t.length() > 90 ? t.substring(0, 90) + "..." : t) + ")";
    }
}
