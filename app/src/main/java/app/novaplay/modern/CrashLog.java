package app.novaplay.modern;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps a note of what went wrong the last time the app closed unexpectedly, where the next start can show it.
 *
 * A phone gives no way to read an app's log, so without this a crash leaves nothing behind but the system's
 * "NovaPlay has stopped" box. The note holds the error with its stack trace, the app / Android / WebView versions and
 * the end of the video connection log, and never a full web address (see {@link #clean}).
 */
final class CrashLog {
    private static final String FRESH = "problem-report.txt";
    private static final String SEEN = "problem-report-seen.txt";
    private static final String PREFS = "crashlog";
    private static final int MAX_FILE = 24 * 1024;
    private static final int MAX_TRACE = 6000;
    private static final int LOG_LINES = 25;
    /** A web address: only its host is kept in a report. */
    private static final Pattern URL = Pattern.compile("(https?://[^/\\s\"'<>)\\]]+)[^\\s\"'<>)\\]]*");

    private static volatile File dir;
    private static volatile String env = "";

    private CrashLog() {
    }

    /**
     * Call first thing in the app: from now on a crash anywhere in it is written down before the app closes, and
     * then handled the way the system always does (the "has stopped" box).
     */
    static void install(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            dir = app.getFilesDir();
            env = environment(app);
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous instanceof Handler) return; // already installed (the activity was created again)
            Thread.setDefaultUncaughtExceptionHandler(new Handler(previous));
        } catch (Throwable ignored) {
            // a crash log must never be the reason the app does not start
        }
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        Handler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                write("The app crashed", t, e, null);
            } catch (Throwable ignored) {
                // nothing more can be done
            }
            if (previous != null) {
                previous.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    /** Writes a report for something that went wrong without taking the app down. */
    static void record(String what, Throwable e) {
        try {
            write(what, Thread.currentThread(), e, null);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /* ---------- reading ---------- */

    /** The newest report (shown or not), or null. */
    static String read() {
        try {
            File d = dir;
            if (d == null) return null;
            File f = new File(d, FRESH);
            if (!f.isFile()) f = new File(d, SEEN);
            if (!f.isFile()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), MAX_FILE)];
            InputStream in = new FileInputStream(f);
            try {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                return new String(b, 0, n, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** True while the newest report has not been shown to the person yet. */
    static boolean fresh() {
        File d = dir;
        return d != null && new File(d, FRESH).isFile();
    }

    /** The report was shown: keep it (Settings still offers it) but stop announcing it at start. */
    static void markSeen() {
        try {
            File d = dir;
            if (d == null) return;
            File f = new File(d, FRESH);
            if (!f.isFile()) return;
            File s = new File(d, SEEN);
            if (s.exists() && !s.delete()) return;
            if (!f.renameTo(s)) f.delete();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    static void clear() {
        try {
            File d = dir;
            if (d == null) return;
            new File(d, FRESH).delete();
            new File(d, SEEN).delete();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /* ---------- what the system recorded about the last run ---------- */

    /**
     * At start (Android 11 and later): when the system ended the app's last run in a way a user would notice (a
     * crash in native code, a hang, the system running out of memory while the app was on screen) and no report was
     * written, writes one from what the system recorded.
     */
    static void checkLastExit(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long seen = sp.getLong("exit_seen", 0);
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 6);
            long newest = seen;
            ApplicationExitInfo bad = null;
            if (list != null) {
                for (ApplicationExitInfo i : list) { // newest first
                    long ts = i.getTimestamp();
                    if (ts <= seen) continue;
                    if (ts > newest) newest = ts;
                    if (bad == null && noticeable(i)) bad = i;
                }
            }
            if (newest > seen) sp.edit().putLong("exit_seen", newest).apply();
            // the very first start has nothing to compare with: older exits are not "last time"
            if (seen == 0 || bad == null || fresh()) return;
            StringBuilder what = new StringBuilder("Android ended the app: ").append(reasonName(bad.getReason()));
            String d = bad.getDescription();
            if (d != null && !d.isEmpty()) what.append(" (").append(d).append(')');
            StringBuilder extra = new StringBuilder();
            extra.append("exit time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                    .format(new Date(bad.getTimestamp()))).append('\n');
            extra.append("was ").append(importanceName(bad.getImportance())).append(", memory use ")
                    .append(bad.getRss() / 1024).append(" MB\n");
            String trace = traceStrings(bad);
            if (!trace.isEmpty()) extra.append("\nsystem trace (text found in it):\n").append(trace).append('\n');
            write(what.toString(), null, null, extra.toString());
        } catch (Throwable ignored) {
            // a missing report is better than a failing start
        }
    }

    @TargetApi(30)
    private static boolean noticeable(ApplicationExitInfo i) {
        switch (i.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return true;
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_SIGNALED:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                // an app that was killed in the background lost nothing anyone saw
                return i.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            default:
                return false;
        }
    }

    @TargetApi(30)
    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH:
                return "it crashed";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "it crashed in native code";
            case ApplicationExitInfo.REASON_ANR:
                return "it stopped responding";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "it failed to start";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "the phone ran out of memory";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "it was killed";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "it used too many resources";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "a part it depends on died";
            default:
                return "reason " + r;
        }
    }

    @TargetApi(30)
    private static String importanceName(int i) {
        if (i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "on screen";
        if (i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return "playing in the background";
        if (i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "visible";
        return "in the background";
    }

    /** The readable text inside the system's trace (a native crash's trace is binary, an ANR's is text). */
    @TargetApi(30)
    private static String traceStrings(ApplicationExitInfo info) {
        try {
            InputStream in = info.getTraceInputStream();
            if (in == null) return "";
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (bo.size() < 96 * 1024 && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return printable(bo.toByteArray(), 3500);
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return "";
        }
    }

    /** Runs of readable characters (at least 6) in [b], one per line, up to [max] characters in all. */
    static String printable(byte[] b, int max) {
        StringBuilder out = new StringBuilder();
        int start = -1;
        for (int i = 0; i <= b.length; i++) {
            boolean ok = i < b.length && b[i] >= 0x20 && b[i] < 0x7f;
            if (ok) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                if (i - start >= 6) {
                    if (out.length() + (i - start) + 1 > max) break;
                    out.append(new String(b, start, i - start, StandardCharsets.US_ASCII)).append('\n');
                }
                start = -1;
            }
        }
        return out.toString().trim();
    }

    /* ---------- writing ---------- */

    private static synchronized void write(String what, Thread t, Throwable e, String extra) throws IOException {
        File d = dir;
        if (d == null) return;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("NovaPlay problem report\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n');
        sb.append(env);
        Runtime rt = Runtime.getRuntime();
        sb.append("memory: app heap ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" of ")
                .append(rt.maxMemory() >> 20).append(" MB\n");
        sb.append("what: ").append(what).append('\n');
        if (t != null) sb.append("thread: ").append(t.getName()).append('\n');
        if (extra != null) sb.append(extra);
        if (e != null) sb.append('\n').append(trace(e)).append('\n');
        String log = tail(MediaLog.dump(), LOG_LINES);
        if (!log.isEmpty()) sb.append("\nvideo connection log (last lines):\n").append(log).append('\n');
        byte[] b = clean(sb.toString()).getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_FILE) b = Arrays.copyOf(b, MAX_FILE);
        FileOutputStream o = new FileOutputStream(new File(d, FRESH));
        try {
            o.write(b);
            o.flush();
        } finally {
            o.close();
        }
        new File(d, SEEN).delete();
    }

    private static String trace(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
        String s = sw.toString();
        if (s.length() <= MAX_TRACE) return s.trim();
        // the top of the trace says where it happened, the end says why
        return s.substring(0, MAX_TRACE * 2 / 3).trim() + "\n  ...\n" + s.substring(s.length() - MAX_TRACE / 3).trim();
    }

    private static String tail(String text, int lines) {
        if (text == null || text.isEmpty()) return "";
        String[] l = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, l.length - lines); i < l.length; i++) {
            if (!l[i].trim().isEmpty()) sb.append(l[i]).append('\n');
        }
        return sb.toString().trim();
    }

    /** Web addresses shrink to their host: a report is meant to be shared, and addresses can carry tokens. */
    static String clean(String s) {
        return URL.matcher(s).replaceAll("$1/...");
    }

    @SuppressWarnings("deprecation")
    private static String environment(Context app) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            sb.append("app: NovaPlay ").append(pi.versionName).append(" (").append(code).append(")\n");
        } catch (Throwable ignored) {
            sb.append("app: NovaPlay\n");
        }
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ")
                .append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            PackageInfo wv = WebView.getCurrentWebViewPackage();
            if (wv != null) sb.append("webview: ").append(wv.packageName).append(' ').append(wv.versionName).append('\n');
        } catch (Throwable ignored) {
            // not available
        }
        return sb.toString();
    }
}
