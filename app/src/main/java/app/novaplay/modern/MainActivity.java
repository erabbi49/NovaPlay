package app.novaplay.modern;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Rational;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NovaPlay 2: a thin native shell. The whole UI lives in assets/ui (HTML, CSS, JS).
 * The shell adds what a web page cannot do on its own: downloads, picture-in-picture,
 * fullscreen/orientation, background playback, network-level ad blocking and fetching the
 * video files with the site's own headers (see MediaProxy).
 */
public class MainActivity extends Activity {
    private static final int BG_DARK = 0xFF0F0F0F;

    /** The WebView's cookies for a URL, for the video requests MediaProxy makes. */
    private static final MediaProxy.Cookies COOKIES = new MediaProxy.Cookies() {
        @Override
        public String get(String url) {
            try {
                return CookieManager.getInstance().getCookie(url);
            } catch (Exception e) {
                return null;
            }
        }
    };

    private static final String PAGE = "file:///android_asset/ui/index.html";
    private static final int REQ_VPN = 71;

    /** The built-in WARP. One per process: the tunnel it brings up belongs to the process, not to this screen. */
    private static WarpController warpController;

    private static synchronized WarpController warp(Context c) {
        if (warpController == null) {
            Context app = c.getApplicationContext();
            WgEngine engine = new WgEngine(app);
            final WarpController w = new WarpController(new WarpStore(new File(app.getNoBackupFilesDir(), "warp.properties")),
                    engine, WarpController.NET, app.getPackageName());
            engine.setOnUnexpectedDown(new Runnable() {
                @Override
                public void run() {
                    w.onTunnelDown();
                }
            });
            warpController = w;
        }
        return warpController;
    }

    /** What WARP needs from this screen: the VPN question, a look at the network, a way to tell the page. */
    private final WarpController.Host warpHost = new WarpController.Host() {
        @Override
        public boolean vpnGranted() {
            try {
                return VpnService.prepare(MainActivity.this) == null;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public void askVpn() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = VpnService.prepare(MainActivity.this);
                        if (i == null) {
                            warp(MainActivity.this).permissionResult(true);
                        } else {
                            startActivityForResult(i, REQ_VPN);
                        }
                    } catch (RuntimeException e) {
                        WarpLog.add("the VPN request could not be shown (" + e.getClass().getSimpleName() + ")");
                        warp(MainActivity.this).permissionResult(false);
                    }
                }
            });
        }

        @Override
        public boolean online() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (RuntimeException e) {
                return true; // cannot tell: try anyway
            }
        }

        @Override
        public void changed() {
            pushWarp();
        }
    };

    private FrameLayout root;
    private WebView web;
    private String ua = "";
    /** Hands the video to the player over plain HTTP on 127.0.0.1 (see LocalMediaServer). */
    private LocalMediaServer media;
    /** Hosts (besides the site's own) the page is about to play video from; filled by Bridge.allowMedia. */
    private final Set<String> mediaDomains = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** When the page was last rebuilt after its process died (to give up if that keeps happening). */
    private final ArrayList<Long> rebuilds = new ArrayList<Long>();
    private volatile boolean playing = false;
    private volatile boolean autoPip = true;
    private boolean foreground = false;
    private boolean immersive = false;
    private boolean lightIcons = true;
    private int barsBg = BG_DARK;

    @Override
    protected void onCreate(Bundle state) {
        CrashLog.install(this); // first of all: a crash from here on leaves a report the next start can show
        super.onCreate(state);
        CrashLog.checkLastExit(this);
        AdBlock.init(getApplicationContext());

        root = new FrameLayout(this);
        root.setBackgroundColor(BG_DARK);
        setContentView(root);
        applyBars();

        web = createWebView();
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        media = new LocalMediaServer(ua, COOKIES);
        web.loadUrl(PAGE);
        WarpController w = warp(this);
        w.setHost(warpHost);
        w.autoStart(); // brings WARP up again when it was left on (never shows the VPN question by itself)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** A WebView set up for the app's page (made again when the page's process dies, see recoverFromRenderGone). */
    private WebView createWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(barsBg);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        if (ua.isEmpty()) ua = s.getUserAgentString();
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new Bridge(), "NovaNative");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    return intercept(request);
                } catch (RuntimeException e) {
                    // a request must never take the app down: the WebView then makes it itself
                    MediaLog.add("request filter error: " + LocalMediaServer.describe(e));
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                // the app page never navigates away; block redirects and pop-unders
                return !"file".equals(request.getUrl().getScheme());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recoverFromRenderGone(view, detail != null && detail.didCrash());
                return true; // handled: the app stays up
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        return w;
    }

    /** Ad blocking, and the site's video files fetched with the site's headers (see MediaProxy). */
    private WebResourceResponse intercept(WebResourceRequest request) {
        Uri u = request.getUrl();
        String scheme = u.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
        String host = u.getHost();
        // the app's own video server is an ordinary HTTP server to the network stack; leave it alone
        if (LocalMediaServer.isLoopback(host)) return null;
        if (AdBlock.isBlocked(host)) {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        // video files the page loads itself (when the local server is not used): the site's servers refuse a
        // request without the site's Referer, so fetch it with one
        if ("GET".equalsIgnoreCase(request.getMethod())
                && MediaProxy.wants(host, u.getPath(), request.getRequestHeaders(), mediaDomains)) {
            MediaProxy.Reply r = MediaProxy.fetch(u.toString(), request.getRequestHeaders(), ua, COOKIES);
            String range = request.getRequestHeaders() == null ? null : request.getRequestHeaders().get("Range");
            MediaLog.add("direct " + host + " " + (range == null ? "all" : range.replace("bytes=", ""))
                    + (r == null ? ": no answer" : " -> " + r.status));
            if (r != null) {
                try {
                    return new WebResourceResponse(r.mime, null, r.status, r.reason, r.headers, r.body);
                } catch (IllegalArgumentException e) {
                    try {
                        r.body.close();
                    } catch (Exception ignored) {
                        // nothing left to close
                    }
                }
            }
        }
        return null;
    }

    /**
     * The page's process died: it crashed, or the system took it back for memory. Left alone that takes the whole app
     * down with it ("NovaPlay has stopped"); instead the page is built again and reloaded, and a crash is written down
     * for the report sheet.
     */
    private void recoverFromRenderGone(WebView dead, boolean crashed) {
        if (dead != web) return; // a view that was already replaced
        // the system taking a backgrounded page back for memory is routine and worth no report
        if (crashed || foreground) {
            CrashLog.record(crashed ? "The page's rendering process crashed"
                    : "The system stopped the page's process (memory) while the app was open", null);
        }
        MediaLog.add("page process " + (crashed ? "crashed" : "was stopped by the system") + "; loading it again");
        playing = false;
        stopService(new Intent(this, PlayService.class));
        long now = SystemClock.elapsedRealtime();
        rebuilds.add(now);
        while (!rebuilds.isEmpty() && now - rebuilds.get(0) > 60000L) rebuilds.remove(0);
        root.removeView(dead);
        web = null;
        try {
            dead.destroy();
        } catch (RuntimeException ignored) {
            // already gone
        }
        if (rebuilds.size() > 3) { // it dies as soon as it loads: stop here instead of looping
            Toast.makeText(this, "NovaPlay's page keeps crashing. Close the app and open it again.", Toast.LENGTH_LONG).show();
            return;
        }
        web = createWebView();
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web.loadUrl(PAGE);
    }

    /* ---------- system bars / fullscreen ---------- */

    @SuppressWarnings("deprecation")
    private void applyBars() {
        Window w = getWindow();
        View d = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = immersive
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(lp);
        }
        w.setStatusBarColor(barsBg);
        w.setNavigationBarColor(barsBg);
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (immersive) {
            f |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else if (!lightIcons) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(f);
    }

    /* ---------- picture-in-picture ---------- */

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build());
        } catch (Exception ignored) {
            // PiP not available on this device
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playing && autoPip) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        if (web != null) web.evaluateJavascript("window.__onPip&&window.__onPip(" + inPip + ")", null);
    }

    /* ---------- built-in WARP ---------- */

    /** Tells the page the WARP state (the page also asks for it when it needs it). */
    private void pushWarp() {
        final WarpController w = warpController;
        if (w == null) return;
        final String json = w.json();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript("window.__onWarp&&window.__onWarp(" + json + ")", null);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) warp(this).permissionResult(resultCode == RESULT_OK);
    }

    /* ---------- lifecycle ---------- */

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return window.__nativeBack?window.__nativeBack():false}catch(e){return false}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if ("true".equals(v)) return;
                        if (playing) moveTaskToBack(true);
                        else finish();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (!playing) stopService(new Intent(this, PlayService.class));
    }

    @Override
    protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, PlayService.class));
        if (warpController != null) warpController.clearHost(warpHost);
        if (media != null) {
            media.stop();
            media = null;
        }
        if (web != null) {
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ---------- JavaScript bridge (window.NovaNative) ---------- */

    public class Bridge {
        @JavascriptInterface
        public void setPlaying(final boolean p, final String title) {
            playing = p;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(MainActivity.this, PlayService.class);
                    if (p) {
                        i.putExtra("t", title == null ? "" : title);
                        try {
                            startForegroundService(i);
                        } catch (Exception ignored) {
                            // not allowed from the background; the service keeps running if it was started
                        }
                    } else if (foreground) {
                        stopService(i);
                    }
                }
            });
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void immersive(final boolean on) {
            immersive = on;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyBars();
                }
            });
        }

        @JavascriptInterface
        public void orientation(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setRequestedOrientation("landscape".equals(mode)
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
            });
        }

        @JavascriptInterface
        public void setBars(final String hex, final boolean light) {
            try {
                barsBg = Color.parseColor(hex);
            } catch (Exception ignored) {
                barsBg = BG_DARK;
            }
            lightIcons = light;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web != null) web.setBackgroundColor(barsBg);
                    applyBars();
                }
            });
        }

        @JavascriptInterface
        public void setAutoPip(boolean on) {
            autoPip = on;
        }

        /** The page is about to play [url]: its host's video files are fetched with the site's headers too. */
        @JavascriptInterface
        public void allowMedia(String url) {
            try {
                String h = MediaProxy.registrable(Uri.parse(url).getHost());
                if (h != null && !h.isEmpty()) mediaDomains.add(h.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
                // not a usable URL
            }
        }

        /**
         * The address on the app's own video server that serves [url] to the player, or "" when that is not
         * available (the player then loads [url] itself).
         */
        @JavascriptInterface
        public String proxyUrl(String url) {
            try {
                LocalMediaServer m = media;
                String u = m == null ? null : m.register(url);
                return u == null ? "" : u;
            } catch (Exception e) {
                return "";
            }
        }

        /** What the video connection did lately, one line per event (Settings shows it). */
        @JavascriptInterface
        public String mediaLog() {
            return MediaLog.dump();
        }

        @JavascriptInterface
        public void logMedia(String line) {
            MediaLog.add("page: " + line);
        }

        /** The WARP state as JSON: {"state","msg","wanted","all","port"}; never holds keys. */
        @JavascriptInterface
        public String warpState() {
            try {
                return warp(MainActivity.this).json();
            } catch (RuntimeException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void warpSet(boolean on) {
            try {
                WarpController w = warp(MainActivity.this);
                if (on) w.turnOn();
                else w.turnOff();
            } catch (RuntimeException e) {
                WarpLog.add("warpSet: " + WarpController.describe(e));
            }
        }

        @JavascriptInterface
        public void warpRetry() {
            try {
                warp(MainActivity.this).retry();
            } catch (RuntimeException e) {
                WarpLog.add("warpRetry: " + WarpController.describe(e));
            }
        }

        @JavascriptInterface
        public void warpAll(boolean all) {
            try {
                warp(MainActivity.this).setWholePhone(all);
            } catch (RuntimeException e) {
                WarpLog.add("warpAll: " + WarpController.describe(e));
            }
        }

        @JavascriptInterface
        public void warpForget() {
            try {
                warp(MainActivity.this).forget();
            } catch (RuntimeException e) {
                WarpLog.add("warpForget: " + WarpController.describe(e));
            }
        }

        /** What the WARP connection did lately, one line per event (Settings > WARP details shows it). */
        @JavascriptInterface
        public String warpLog() {
            return WarpLog.dump();
        }

        @JavascriptInterface
        public void pip() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    enterPip();
                }
            });
        }

        /**
         * The last problem report as {"text": "...", "fresh": true|false} ("fresh": not shown to the person yet), or
         * "" when there is none.
         */
        @JavascriptInterface
        public String crashReport() {
            try {
                String t = CrashLog.read();
                if (t == null || t.isEmpty()) return "";
                JSONObject o = new JSONObject();
                o.put("text", t);
                o.put("fresh", CrashLog.fresh());
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** The report was shown: stop announcing it at start (it stays available in Settings). */
        @JavascriptInterface
        public void crashSeen() {
            CrashLog.markSeen();
        }

        @JavascriptInterface
        public void crashClear() {
            CrashLog.clear();
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NovaPlay", text == null ? "" : text));
                    } catch (RuntimeException ignored) {
                        // the clipboard is not available
                    }
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(i, null));
                    } catch (RuntimeException e) {
                        Toast.makeText(MainActivity.this, "Sharing isn’t available", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void toast(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        @JavascriptInterface
        public String download(String url, String name, String title) {
            try {
                String clean = (name == null ? "video.mp4" : name.replaceAll("[\\\\/:*?\"<>|]+", " ").trim());
                if (clean.isEmpty()) clean = "video.mp4";
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.addRequestHeader("Referer", "https://www.eporner.com/");
                r.addRequestHeader("User-Agent", ua);
                r.setTitle(title == null ? "NovaPlay" : title);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setAllowedOverMetered(true);
                r.setAllowedOverRoaming(true);
                r.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_MOVIES, "NovaPlay/" + clean);
                return String.valueOf(dm.enqueue(r));
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String downloads() {
            JSONArray arr = new JSONArray();
            Cursor c = null;
            try {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                c = dm.query(new DownloadManager.Query());
                while (c != null && c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
                    o.put("title", c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)));
                    o.put("status", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
                    o.put("bytes", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                    o.put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                    o.put("uri", c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                    arr.put(o);
                }
            } catch (Exception ignored) {
                // return whatever was read
            } finally {
                if (c != null) c.close();
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void removeDownload(String id) {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.remove(Long.parseLong(id));
            } catch (Exception ignored) {
                // nothing to remove
            }
        }
    }
}
