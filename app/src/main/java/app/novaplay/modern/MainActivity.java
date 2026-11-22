package app.novaplay.modern;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Rational;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

/**
 * NovaPlay 2: a thin native shell. The whole UI lives in assets/ui (HTML, CSS, JS).
 * The shell adds what a web page cannot do on its own: downloads, picture-in-picture,
 * fullscreen/orientation, background playback and network-level ad blocking.
 */
public class MainActivity extends Activity {
    private static final int BG_DARK = 0xFF0F0F0F;

    private WebView web;
    private String ua = "";
    private volatile boolean playing = false;
    private volatile boolean autoPip = true;
    private boolean foreground = false;
    private boolean immersive = false;
    private boolean lightIcons = true;
    private int barsBg = BG_DARK;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AdBlock.init(getApplicationContext());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_DARK);
        web = new WebView(this);
        web.setBackgroundColor(BG_DARK);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyBars();

        WebSettings s = web.getSettings();
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
        ua = s.getUserAgentString();
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "NovaNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme)) && AdBlock.isBlocked(u.getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                // the app page never navigates away; block redirects and pop-unders
                return !"file".equals(request.getUrl().getScheme());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        web.loadUrl("file:///android_asset/ui/index.html");

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
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

        @JavascriptInterface
        public void pip() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    enterPip();
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, null));
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
