package app.novaplay.modern;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * What the built-in WARP remembers between runs, in one small file in the app's private storage: whether the person
 * wants it on, whether it covers the whole phone, and the account Cloudflare gave this phone (see {@link WarpAccount}).
 * The file is kept out of backups by its caller (it holds this phone's private key).
 */
final class WarpStore {
    private static final String[] ACCOUNT_KEYS = {"priv", "peer", "v4", "v6", "endpoint", "ports", "id", "token"};

    private final File file;
    private final Properties props = new Properties();
    private boolean loaded;

    WarpStore(File file) {
        this.file = file;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
        } catch (IOException ignored) {
            // no file yet, or it cannot be read: start empty
        } catch (IllegalArgumentException ignored) {
            props.clear(); // a damaged file: start empty
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private void save() {
        File tmp = new File(file.getPath() + ".tmp");
        OutputStream out = null;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            out = new FileOutputStream(tmp);
            props.store(out, null);
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                // some file systems will not rename over an existing file
                if (file.exists() && !file.delete()) return;
                if (!tmp.renameTo(file)) WarpLog.add("could not save the WARP settings");
            }
        } catch (IOException e) {
            WarpLog.add("could not save the WARP settings (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** The saved account, or null when there is none or it is damaged. */
    synchronized WarpAccount account() {
        load();
        return WarpAccount.from(props);
    }

    synchronized void saveAccount(WarpAccount a) {
        load();
        a.putInto(props);
        save();
    }

    synchronized void forgetAccount() {
        load();
        for (String k : ACCOUNT_KEYS) props.remove(k);
        save();
    }

    synchronized boolean wanted() {
        load();
        return "1".equals(props.getProperty("wanted"));
    }

    synchronized void setWanted(boolean on) {
        load();
        props.setProperty("wanted", on ? "1" : "0");
        save();
    }

    /** True when the tunnel carries the whole phone's traffic instead of only this app's. */
    synchronized boolean wholePhone() {
        load();
        return "1".equals(props.getProperty("all"));
    }

    synchronized void setWholePhone(boolean on) {
        load();
        props.setProperty("all", on ? "1" : "0");
        save();
    }
}
