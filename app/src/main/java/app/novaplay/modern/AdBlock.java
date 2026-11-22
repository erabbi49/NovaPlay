package app.novaplay.modern;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;

/**
 * Network-level ad/tracker blocking. assets/hosts.txt is built from EasyList, EasyPrivacy and
 * uBlock Origin filter lists (domain rules only). Requests to a listed host, or to any of its
 * sub-domains, are answered with an empty response before they leave the phone.
 */
final class AdBlock {
    private static final HashSet<String> HOSTS = new HashSet<String>(1 << 17);
    private static volatile boolean ready = false;

    private AdBlock() {
    }

    static void init(final Context ctx) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("hosts.txt"), "UTF-8"));
                    HashSet<String> tmp = new HashSet<String>(1 << 17);
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) tmp.add(line);
                    }
                    r.close();
                    HOSTS.addAll(tmp);
                    ready = true;
                } catch (Exception ignored) {
                    // blocking stays off if the list cannot be read
                }
            }
        }, "adblock-init").start();
    }

    static boolean isBlocked(String host) {
        if (!ready || host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith("eporner.com") || h.endsWith("epcdn.com")) return false;
        while (true) {
            if (HOSTS.contains(h)) return true;
            int i = h.indexOf('.');
            if (i < 0) return false;
            String rest = h.substring(i + 1);
            if (rest.indexOf('.') < 0) return false; // never test a bare TLD
            h = rest;
        }
    }
}
