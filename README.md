# NovaPlay 2

YouTube-style app for eporner. A small native Android shell (WebView, downloads, picture-in-picture,
background play, ad blocking) hosts the whole UI, which is plain HTML/CSS/JS.

## Get the APK (GitHub, no PC tools needed)
1. Create a new GitHub repository and upload everything in this folder. Keep the folder structure and
   include the hidden `.github` folder.
2. Open the **Actions** tab, pick **Build APK**, press **Run workflow**.
3. When it finishes, download the **NovaPlay-APK** artifact (a zip) and install the `.apk` inside.
   Every build uses the same signing key, so new builds install over old ones.

## Build locally
Open this folder in Android Studio and run Build > Build APK(s), or run `gradle assembleRelease`
(JDK 17, Android SDK 34).

## Where things are
- `app/src/main/assets/ui/`   the whole UI (`index.html`, `style.css`, `app.js`)
- `app/src/main/assets/hosts.txt`   ad and tracker hosts, derived from EasyList, EasyPrivacy and uBlock Origin filter lists
- `app/src/main/java/app/novaplay/modern/`   the native shell

## Notes
- Video lists come from the public eporner API. Stream links are read from the video page the same way the
  site's own player does. If that ever stops working, tap "Use web player" on the error screen.
- The 18+ confirmation appears on first launch.
- The site's video servers only hand a file to requests that look like they come from the site's own pages (anything else gets a short "only available at ..." clip). `MediaProxy.java` fetches the video files itself with the site's Referer. `LocalMediaServer.java` is a small HTTP server on 127.0.0.1 that hands those files to the player, Range requests included, so playing, seeking and buffering behave like they do on a normal web page. If a video still turns out to be that short clip, the app opens the site's own player for it.
- If a video won't load, Settings > Video connection log shows what the connection did (host names, byte ranges and sizes only); "Copy log" / "Share log" send it as text.
- If the app stops unexpectedly, `CrashLog.java` writes down what happened (the error, app / Android / WebView versions, the end of the video log; web addresses are cut down to their host). The next start shows it in a sheet with Copy / Share / Delete, and Settings > Problem report keeps the last one. On Android 11+ it also reports what the system recorded about a native crash or a memory kill. If the page's own process dies, the app builds the page again instead of closing.
- `LocalMediaServer.java` threads follow one rule: a thread that does not read a stream never closes it (the platform's HTTP client throws unchecked exceptions when that happens, which used to end the app when the player seeked). The watcher thread only cuts the connection (`MediaProxy.Reply.abort()`); the thread that serves the connection releases the stream.
