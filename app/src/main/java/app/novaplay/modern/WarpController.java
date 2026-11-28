package app.novaplay.modern;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The built-in Cloudflare WARP (what the 1.1.1.1 app does): registers this phone with Cloudflare once, then brings a
 * WireGuard tunnel up and checks that the traffic really goes through it. All the slow work (network, tunnel) runs on one
 * worker thread; every request from the screen only changes the wanted state and queues work, so the screen never waits.
 *
 * The pieces that talk to the system are behind small interfaces so the whole flow can be tested without a phone.
 */
final class WarpController {
    /** The WireGuard side: makes keys and brings the tunnel up or down. */
    interface Engine {
        /** A new private key, base64. */
        String newPrivateKey() throws Exception;

        /** The public key (base64) that belongs to a private key (base64). */
        String publicKeyOf(String privateKey) throws Exception;

        /** Brings the tunnel up from a wg-quick style configuration (replacing one that is already up). */
        void up(String wgQuick) throws Exception;

        void down() throws Exception;
    }

    /** The network side: Cloudflare's API and the "is this really WARP?" check. */
    interface Net {
        WarpApi.Registration register(String publicKey) throws IOException;

        /** {@link WarpApi#PROBE_WARP}, {@link WarpApi#PROBE_DIRECT} or {@link WarpApi#PROBE_NONE}. */
        int probe();
    }

    /** The screen that is showing now. */
    interface Host {
        /** True when the system's "allow this app to set up a VPN" question has been answered yes. */
        boolean vpnGranted();

        /** Shows that question; the answer comes back through {@link #permissionResult}. */
        void askVpn();

        /** True when the phone has some network to try. */
        boolean online();

        /** The state changed (may be called from any thread). */
        void changed();
    }

    static final String OFF = "off";
    static final String CONNECTING = "connecting";
    static final String REGISTERING = "registering";
    static final String PERMISSION = "permission";
    static final String ON = "on";
    static final String ERROR = "error";

    /** The real network side. */
    static final Net NET = new Net() {
        @Override
        public WarpApi.Registration register(String publicKey) throws IOException {
            return WarpApi.register(publicKey);
        }

        @Override
        public int probe() {
            return WarpApi.probe();
        }
    };

    private final WarpStore store;
    private final Engine engine;
    private final Net net;
    private final String appId;
    private final ExecutorService worker;
    private volatile Host host;

    private final Object lock = new Object();
    private String state = OFF;
    private String message = "";
    private int port;
    private int gen; // bumped by every start, stop and restart; work that finds it changed gives up

    WarpController(WarpStore store, Engine engine, Net net, String appId) {
        this(store, engine, net, appId, Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nova-warp");
                t.setDaemon(true);
                return t;
            }
        }));
    }

    WarpController(WarpStore store, Engine engine, Net net, String appId, ExecutorService worker) {
        this.store = store;
        this.engine = engine;
        this.net = net;
        this.appId = appId;
        this.worker = worker;
    }

    void setHost(Host h) {
        host = h;
    }

    /** The screen is going away; forgets it unless a newer screen has taken its place already. */
    void clearHost(Host h) {
        if (host == h) host = null;
    }

    /* ---------- what the screen asks for ---------- */

    /** At app start: brings WARP up again when the person left it on. Never shows the permission question by itself. */
    void autoStart() {
        if (store.wanted()) run(false, false);
    }

    /** The person turned WARP on (or asked to try again). */
    void turnOn() {
        store.setWanted(true);
        run(true, false);
    }

    /** Starts again from the beginning, even when WARP is on (the "Reconnect" button). */
    void retry() {
        store.setWanted(true);
        run(true, true);
    }

    /** The person turned WARP off. */
    void turnOff() {
        store.setWanted(false);
        synchronized (lock) {
            gen++;
            setLocked(OFF, "");
        }
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.down();
                } catch (Throwable t) {
                    WarpLog.add("turning off: " + describe(t));
                }
            }
        });
    }

    /** The answer to the system's VPN question. */
    void permissionResult(boolean granted) {
        final int g;
        synchronized (lock) {
            if (!PERMISSION.equals(state)) return;
            g = ++gen;
            if (granted) setLocked(CONNECTING, "Connecting…");
            else setLocked(OFF, "The VPN request was not allowed");
        }
        if (!granted) {
            WarpLog.add("the VPN request was not allowed");
            store.setWanted(false);
            changed();
            return;
        }
        WarpLog.add("the VPN request was allowed");
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                connect(g, false);
            }
        });
    }

    /** Throws away the account Cloudflare gave this phone; a new one is made the next time WARP starts. */
    void forget() {
        synchronized (lock) {
            gen++;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.down();
                } catch (Throwable t) {
                    WarpLog.add("stopping the tunnel: " + describe(t));
                }
            }
        });
        store.forgetAccount();
        WarpLog.add("the saved WARP account was deleted");
        if (store.wanted()) {
            run(true, true);
        } else {
            synchronized (lock) {
                setLocked(OFF, "");
            }
            changed();
        }
    }

    /** Whether the tunnel carries the whole phone (true) or only this app (false). Applied at once when WARP is on. */
    void setWholePhone(boolean all) {
        store.setWholePhone(all);
        boolean active;
        synchronized (lock) {
            active = ON.equals(state) || CONNECTING.equals(state) || REGISTERING.equals(state);
        }
        if (active && store.wanted()) run(false, true);
    }

    /** The tunnel went down without being asked to (another VPN app took over, or the system stopped it). */
    void onTunnelDown() {
        boolean was;
        synchronized (lock) {
            was = ON.equals(state);
            if (was) {
                gen++;
                setLocked(ERROR, "WARP was stopped (another VPN app may have taken over). Tap to turn it on again.");
            }
        }
        if (was) {
            WarpLog.add("the tunnel went down by itself");
            changed();
        }
    }

    /** The state for the page, as JSON. Never holds keys or tokens. */
    String json() {
        String s, m;
        int p;
        synchronized (lock) {
            s = state;
            m = message;
            p = port;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("state", s);
            o.put("msg", m);
            o.put("wanted", store.wanted());
            o.put("all", store.wholePhone());
            o.put("port", p);
            return o.toString();
        } catch (JSONException e) {
            return "{\"state\":\"" + s + "\"}";
        }
    }

    String state() {
        synchronized (lock) {
            return state;
        }
    }

    String message() {
        synchronized (lock) {
            return message;
        }
    }

    /** Waits until the work queued so far has run (for tests). */
    void drain(long ms) throws InterruptedException, ExecutionException, TimeoutException {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                // nothing: it only has to get its turn
            }
        }).get(ms, TimeUnit.MILLISECONDS);
    }

    /* ---------- the work ---------- */

    private void run(boolean mayAsk, boolean force) {
        final int g;
        final boolean ask = mayAsk;
        synchronized (lock) {
            if (!force && (ON.equals(state) || CONNECTING.equals(state) || REGISTERING.equals(state))) return;
            g = ++gen;
            setLocked(CONNECTING, "Connecting…");
        }
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                connect(g, ask);
            }
        });
    }

    private void connect(int g, boolean mayAsk) {
        try {
            if (stale(g)) return;
            Host h = host;
            if (h != null && !h.online()) {
                fail(g, "No internet connection. Connect to Wi-Fi or mobile data, then try again.");
                return;
            }
            WarpAccount acct = store.account();
            if (acct == null) {
                set(g, REGISTERING, "Registering with Cloudflare…");
                String priv = engine.newPrivateKey();
                String pub = engine.publicKeyOf(priv);
                WarpApi.Registration r = net.register(pub);
                acct = new WarpAccount(priv, r.peerKey, r.v4, r.v6, r.endpoint, r.ports, r.id, r.token);
                store.saveAccount(acct); // kept even when the person turned WARP off meanwhile
                if (stale(g)) return;
            }
            h = host;
            if (h == null || !h.vpnGranted()) {
                if (!set(g, PERMISSION, mayAsk ? "Allow the VPN request to continue" : "WARP needs your OK for the VPN request. Tap to continue.")) return;
                WarpLog.add("waiting for the system's VPN request to be allowed");
                if (mayAsk && h != null) h.askVpn();
                return;
            }
            tunnel(g, acct);
        } catch (IOException e) {
            fail(g, e.getMessage() == null ? "Could not reach Cloudflare" : e.getMessage());
        } catch (Throwable t) {
            fail(g, "WARP could not start (" + describe(t) + ")");
        }
    }

    private void tunnel(int g, WarpAccount acct) throws Exception {
        boolean all = store.wholePhone();
        String last = "no answer from Cloudflare";
        int[] ports = acct.ports;
        for (int i = 0; i < ports.length; i++) {
            int p = ports[i];
            if (stale(g)) return;
            set(g, CONNECTING, i == 0 ? "Connecting…" : "Connecting (port " + p + ")…");
            WarpLog.add("starting the tunnel to " + acct.endpoint + ":" + p + (all ? " (whole phone)" : " (this app only)"));
            engine.up(acct.wgQuick(all ? "" : appId, p));
            int r = net.probe();
            if (stale(g)) return;
            if (r == WarpApi.PROBE_DIRECT && !all) {
                // the tunnel is up, but this app's own traffic does not use it: widen it to the whole phone
                WarpLog.add("this app's traffic does not use the tunnel; trying it for the whole phone");
                all = true;
                store.setWholePhone(true);
                engine.up(acct.wgQuick("", p));
                r = net.probe();
                if (stale(g)) return;
            }
            if (r == WarpApi.PROBE_WARP) {
                synchronized (lock) {
                    if (g != gen) return;
                    port = p;
                    setLocked(ON, all ? "Connected · whole phone" : "Connected · NovaPlay only");
                }
                WarpLog.add("connected through " + acct.endpoint + ":" + p + (all ? " (whole phone)" : " (this app only)"));
                changed();
                return;
            }
            last = r == WarpApi.PROBE_DIRECT ? "Cloudflare says this traffic does not use WARP" : "no answer from Cloudflare";
            WarpLog.add("port " + p + ": " + last);
            try {
                engine.down();
            } catch (Throwable t) {
                WarpLog.add("stopping the tunnel: " + describe(t));
            }
        }
        fail(g, "Couldn’t connect to WARP on this network (" + last + "). Try again, or open WARP log in Settings.");
    }

    private boolean stale(int g) {
        synchronized (lock) {
            return g != gen;
        }
    }

    /** Sets the state if this work is still the current one; tells the screen. */
    private boolean set(int g, String s, String m) {
        synchronized (lock) {
            if (g != gen) return false;
            setLocked(s, m);
        }
        changed();
        return true;
    }

    private void fail(int g, String m) {
        synchronized (lock) {
            if (g != gen) return;
            setLocked(ERROR, m);
        }
        WarpLog.add("failed: " + m);
        changed();
    }

    private void setLocked(String s, String m) {
        state = s;
        message = m == null ? "" : m;
        if (!ON.equals(s)) port = 0;
    }

    private void changed() {
        Host h = host;
        if (h != null) h.changed();
    }

    static String describe(Throwable t) {
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return n;
        return n + ": " + (m.length() > 160 ? m.substring(0, 160) : m);
    }
}
