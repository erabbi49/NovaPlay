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
- Lists (Home, search, tags, Following) are endless: the first page is drawn at once and the next pages are added under it while you scroll (`listPage()` in `app.js`; the page numbers stay under the list, and jump to a page still works). After 8 pages added by scrolling alone a "Load more videos" button asks first; a page that fails shows a retry button instead of being asked for again and again. Going back to a list that was scrolled through brings its pages back (up to 12), then the place in it.
- Built-in Cloudflare WARP (what the 1.1.1.1 app does), for networks where the site does not load: Settings > Connection > Cloudflare WARP (1.1.1.1). The first time, `WarpApi.java` registers the phone with Cloudflare (the same free WARP account the 1.1.1.1 app makes; the account and the private key stay in the app's no-backup storage, `WarpStore.java`), then `WgEngine.java` brings up a WireGuard tunnel (the official `com.wireguard.android:tunnel` library, which runs wireguard-go and Android's VpnService) and `WarpController.java` checks `https://1.1.1.1/cdn-cgi/trace` (`warp=on`) before it says "Connected". It tries Cloudflare's UDP ports 2408, 500, 4500 and 1701 in turn, because some providers block some of them. The system's VPN question appears once. By default only NovaPlay's own traffic goes through WARP; Settings > WARP details > "Cover the whole phone" sends everything through it (needed for the system's download manager, which runs outside the app). Android allows one VPN at a time, so this replaces another VPN app while it is on. WARP is brought back up at app start when it was left on. An error screen that cannot load videos shows a "Turn on WARP" button and loads again by itself when WARP connects. Settings > WARP details has Copy / Share log (`WarpLog.java`: events and ports only, never keys) and "Reset WARP account".
