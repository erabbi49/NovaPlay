package app.novaplay.modern;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * What it takes to bring the WARP tunnel up: this phone's own private key, and what Cloudflare answered when the phone
 * registered (its address inside the tunnel, the server's key, where to connect).
 */
final class WarpAccount {
    /** Ports Cloudflare's WARP servers listen on; the first that gets an answer through the network wins. */
    static final int[] DEFAULT_PORTS = {2408, 500, 4500, 1701};
    static final String DEFAULT_ENDPOINT = "162.159.192.1";

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9+/]{43}=$");
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]{2,45}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{0,128}$");

    final String privateKey;
    final String peerKey;
    final String v4;
    final String v6;
    final String endpoint;
    final int[] ports;
    final String id;
    final String token;

    WarpAccount(String privateKey, String peerKey, String v4, String v6, String endpoint, int[] ports, String id, String token) {
        this.privateKey = privateKey;
        this.peerKey = peerKey;
        this.v4 = v4;
        this.v6 = v6 == null ? "" : v6;
        this.endpoint = endpoint;
        this.ports = ports == null || ports.length == 0 ? DEFAULT_PORTS : ports;
        this.id = id == null ? "" : id;
        this.token = token == null ? "" : token;
    }

    /** True for values that are safe to put into a configuration text (no spaces, no line breaks, the right shape). */
    static boolean validKey(String s) {
        return s != null && KEY.matcher(s).matches();
    }

    static boolean validV4(String s) {
        if (s == null || !IPV4.matcher(s).matches()) return false;
        for (String p : s.split("\\.")) {
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    static boolean validV6(String s) {
        return s != null && s.indexOf(':') >= 0 && IPV6.matcher(s).matches();
    }

    static boolean validHost(String s) {
        return s != null && (HOST.matcher(s).matches() || validV6(s));
    }

    static boolean validId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    /** The settings in the text form the WireGuard library reads ("wg-quick"), for NovaPlay only (per-app VPN). */
    String wgQuick(String appId, int port) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("[Interface]\n");
        sb.append("PrivateKey = ").append(privateKey).append('\n');
        sb.append("Address = ").append(v4).append("/32");
        if (!v6.isEmpty()) sb.append(", ").append(v6).append("/128");
        sb.append('\n');
        sb.append("DNS = 1.1.1.1, 1.0.0.1");
        if (!v6.isEmpty()) sb.append(", 2606:4700:4700::1111, 2606:4700:4700::1001");
        sb.append('\n');
        sb.append("MTU = 1280\n");
        // only this app's traffic goes through the tunnel; the rest of the phone is left alone
        if (appId != null && !appId.isEmpty()) sb.append("IncludedApplications = ").append(appId).append('\n');
        sb.append("\n[Peer]\n");
        sb.append("PublicKey = ").append(peerKey).append('\n');
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n");
        sb.append("Endpoint = ").append(endpoint.indexOf(':') >= 0 ? "[" + endpoint + "]" : endpoint).append(':').append(port).append('\n');
        sb.append("PersistentKeepalive = 25\n");
        return sb.toString();
    }

    /* ---------- saved form ---------- */

    void putInto(Properties p) {
        p.setProperty("priv", privateKey);
        p.setProperty("peer", peerKey);
        p.setProperty("v4", v4);
        p.setProperty("v6", v6);
        p.setProperty("endpoint", endpoint);
        StringBuilder sb = new StringBuilder();
        for (int x : ports) sb.append(sb.length() == 0 ? "" : ",").append(x);
        p.setProperty("ports", sb.toString());
        p.setProperty("id", id);
        p.setProperty("token", token);
    }

    /** The saved account, or null when there is none or it is incomplete or damaged. */
    static WarpAccount from(Properties p) {
        String priv = p.getProperty("priv");
        String peer = p.getProperty("peer");
        String v4 = p.getProperty("v4");
        String v6 = p.getProperty("v6", "");
        String ep = p.getProperty("endpoint");
        if (!validKey(priv) || !validKey(peer) || !validV4(v4) || !validHost(ep)) return null;
        if (!v6.isEmpty() && !validV6(v6)) return null;
        List<Integer> list = new ArrayList<Integer>();
        String ports = p.getProperty("ports", "");
        for (String s : ports.split(",")) {
            try {
                int x = Integer.parseInt(s.trim());
                if (x > 0 && x < 65536) list.add(x);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        String id = p.getProperty("id", "");
        String token = p.getProperty("token", "");
        return new WarpAccount(priv, peer, v4, v6, ep, arr, validId(id) ? id : "", validId(token) ? token : "");
    }
}
