package app.novaplay.modern;

import android.content.Context;

import com.wireguard.android.backend.BackendException;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The WireGuard side of the built-in WARP: the official WireGuard "tunnel" library, which runs wireguard-go inside this
 * app and sets up Android's VpnService for it. (The library declares the VPN service itself.)
 */
final class WgEngine implements WarpController.Engine {
    private final Context app;
    private GoBackend backend;
    /** True while this class itself is changing the tunnel, so the library's "down" callbacks are not taken for a stop from outside. */
    private volatile boolean busy;
    private volatile Runnable onUnexpectedDown;

    private final Tunnel tunnel = new Tunnel() {
        @Override
        public String getName() {
            return "warp";
        }

        @Override
        public void onStateChange(Tunnel.State newState) {
            WarpLog.add("tunnel is " + newState);
            if (newState == Tunnel.State.DOWN && !busy) {
                Runnable r = onUnexpectedDown;
                if (r != null) r.run();
            }
        }
    };

    WgEngine(Context context) {
        this.app = context.getApplicationContext();
    }

    /** Called when the tunnel goes down without this class having asked for it (another VPN took over, the system stopped it). */
    void setOnUnexpectedDown(Runnable r) {
        onUnexpectedDown = r;
    }

    private synchronized GoBackend backend() throws Exception {
        if (backend == null) {
            try {
                backend = new GoBackend(app); // loads the native library
            } catch (LinkageError e) {
                throw new Exception("the WireGuard engine could not load (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
            }
        }
        return backend;
    }

    @Override
    public String newPrivateKey() {
        return new KeyPair().getPrivateKey().toBase64();
    }

    @Override
    public String publicKeyOf(String privateKey) throws Exception {
        return new KeyPair(Key.fromBase64(privateKey)).getPublicKey().toBase64();
    }

    @Override
    public void up(String wgQuick) throws Exception {
        Config config = Config.parse(new ByteArrayInputStream(wgQuick.getBytes(StandardCharsets.UTF_8)));
        GoBackend b = backend();
        busy = true;
        try {
            b.setState(tunnel, Tunnel.State.UP, config);
        } catch (BackendException e) {
            throw new Exception("the tunnel could not start (" + e.getReason() + ")", e);
        } finally {
            busy = false;
        }
    }

    @Override
    public void down() throws Exception {
        GoBackend b;
        synchronized (this) {
            b = backend;
        }
        if (b == null) return;
        busy = true;
        try {
            b.setState(tunnel, Tunnel.State.DOWN, null);
        } finally {
            busy = false;
        }
    }
}
