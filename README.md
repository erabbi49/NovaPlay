# NovaPlay

A YouTube-style Android app that plays its videos from xhamster instead of YouTube.
Native Kotlin app (no web page wrapper), with a built-in uBlock-Origin-style ad blocker.

## What it has

| | |
|---|---|
| **Home / Search / Channels** | Home feed with chips (Home, Newest, Best, HD, 4K and your own), search (or paste a link), channel and category pages, infinite scroll, pull to refresh |
| **Player** | Own player screen: speed 0.25x-3x, quality picker, fullscreen (rotate or button), double-tap skip, swipe for brightness / volume, long-press for 2x, screen lock, loop, sleep timer, picture-in-picture, background play with notification controls |
| **Ambient mode** | Soft glow behind the player that follows the colours of the video |
| **Queue / Play next** | "Play next", "Add to queue", drag to reorder, swipe to remove, shuffle, save queue as a playlist, autoplay of the queue then related videos |
| **Downloads** | In-app download (HLS and MP4), pause / resume, quality choice, Wi-Fi only option, notification with progress, plays offline, copy to gallery |
| **Library ("You" tab)** | Watch history with resume position, liked videos, watch later, playlists, subscriptions (followed channels with a mixed feed), custom Home chips |
| **Ad blocker** | uBlock Origin / Adblock Plus syntax engine with EasyList, EasyPrivacy, uBlock filters (+privacy, badware, unbreak), EasyList Adult. Network blocking, cosmetic filters, scriptlets, pop-up blocking, your own rules, list updates |
| **Other** | Light / dark / AMOLED theme, share links into the app, open a video link straight from the browser, in-app browser (ad-blocked) for human-checks |

Not included because it cannot work with this source: accounts and sign-in, comments, live chat, Shorts, YouTube-side subscriptions and notifications, casting.

## Build the APK without a computer

The APK is built by GitHub Actions (free). You only need a GitHub account.

1. Create a new repository on GitHub (private is fine) and put the contents of this folder in it.
   With Termux on the phone:
   ```
   pkg install git unzip
   unzip NovaPlay.zip && cd NovaPlay
   git init && git add . && git commit -m "NovaPlay"
   git branch -M main
   git remote add origin https://github.com/<your-name>/<repo>.git
   git push -u origin main      # the password is a Personal Access Token
   ```
2. Open the repository > **Actions** > "Build NovaPlay APK". It starts by itself after the push
   (or press **Run workflow**). It takes about 6-10 minutes.
3. When it is green, open **Releases** (right side of the repo page) > the newest "NovaPlay build N" > download
   `app-release.apk` and install it. Allow "install unknown apps" for your browser if Android asks.

Every later push builds a new APK. They are all signed with the same key (`app/nova-release.keystore`,
committed on purpose, personal use), so a new build installs over the old one and keeps your data.

If the build fails, open the failed run and copy the red error lines; that is all that is needed to fix it.

## Good to know

* **Not tested on a real phone yet.** The code was type-checked and the logic behind it was tested on a computer
  (page scraping against sample pages, the stream cipher, the ad-block engine with the real filter lists, the HLS / MP4
  downloader with pause and resume), but no APK could be built where it was written and the live site could not be
  reached from there. Treat build 1 as a first test build.
* **If the site does not open:** it may be blocked by your network. Try another address of the site
  (Settings > Site address, for example a mirror) or switch off Private DNS / change the DNS in the phone's network settings.
* **"Human check" (Cloudflare):** open the site once in the in-app browser (the error screen has a button, or
  Settings > Open site in browser). NovaPlay shares the cookies with it and continues.
* **If the app closes by itself:** Settings > About > "Last crash report" > Copy, and send that text.
* The page layout of the site can change at any time; the extraction scripts are in `app/src/main/assets/web/`
  (`cards.js` for lists, `video.js` for the video page) and are the first place to adjust when the lists or
  videos stop loading.
* Videos come from the site you choose; NovaPlay hosts nothing. For adults only. Check the law where you live.

## Project layout

```
app/src/main/java/com/novaplay/app
  adblock/   filter parser, engine, lists, WebView hooks
  data/      models, settings, library (history, playlists, downloads ...)
  download/  HLS + MP4 downloaders, download queue, service
  player/    Playback (queue, ExoPlayer), player screen, notification service, ambient mode
  ui/        screens, widgets, settings, ad-blocker panel
  web/       hidden-WebView scraper for the site
app/src/main/assets/{filters,web}   bundled filter lists and page scripts
.github/workflows/build-apk.yml     the CI build
```
