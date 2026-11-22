package com.novaplay.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.novaplay.app.CrashLog
import com.novaplay.app.Nav
import com.novaplay.app.NovaApp
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.web.Site
import com.novaplay.app.web.SiteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The settings, split into pages like the YouTube app: a list of categories, and one page for each. The page to show
 * is passed as [EXTRA_PAGE]; without it the list of categories is shown.
 */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE).orEmpty()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(this))
        val bar = TopBar(this)
        bar.title.text = titleOf(page)
        bar.showBack { finish() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(content, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
        setContentView(root)
        content.addView(Ui.spacer(this, 0, 4))
        val f = Form(this, content)
        when (page) {
            PAGE_APPEARANCE -> appearance(f)
            PAGE_PLAYBACK -> playback(f)
            PAGE_GESTURES -> gestures(f)
            PAGE_AMBIENT -> ambient(f)
            PAGE_DOWNLOADS -> downloads(f)
            PAGE_PRIVACY -> privacy(f)
            PAGE_SITE -> site(f)
            PAGE_ABOUT -> about(f)
            else -> home(f)
        }
        content.addView(Ui.spacer(this, 0, 24))
    }

    private fun titleOf(page: String): String = when (page) {
        PAGE_APPEARANCE -> "Appearance & layout"
        PAGE_PLAYBACK -> "Playback"
        PAGE_GESTURES -> "Gestures"
        PAGE_AMBIENT -> "Ambient mode"
        PAGE_DOWNLOADS -> "Downloads"
        PAGE_PRIVACY -> "History & privacy"
        PAGE_SITE -> "Site"
        PAGE_ABOUT -> "About"
        else -> "Settings"
    }

    private fun open(page: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page))
    }

    // ---------- the list of categories ----------

    private fun home(f: Form) {
        f.nav(R.drawable.ic_tune, "Appearance & layout", "Theme, list layout, mini player, thumbnails") { open(PAGE_APPEARANCE) }
        f.nav(R.drawable.ic_play, "Playback", "Autoplay, speed, quality, background play") { open(PAGE_PLAYBACK) }
        f.nav(R.drawable.ic_forward, "Gestures", "Swipes, double-tap, hold to speed up, pinch to zoom") { open(PAGE_GESTURES) }
        f.nav(R.drawable.ic_ambient, "Ambient mode", "Light that follows the colours of the video") { open(PAGE_AMBIENT) }
        f.nav(R.drawable.ic_download, "Downloads", "Quality and Wi-Fi") { open(PAGE_DOWNLOADS) }
        f.nav(R.drawable.ic_shield, "Ad blocker", "Filter lists, switches and your own rules") {
            startActivity(Intent(this, AdBlockActivity::class.java))
        }
        f.nav(R.drawable.ic_history, "History & privacy", "Watch history and searches") { open(PAGE_PRIVACY) }
        f.nav(R.drawable.ic_browser, "Site", "Address of the site and the in-app browser") { open(PAGE_SITE) }
        f.nav(R.drawable.ic_info, "About", "Version, crash report, diagnostics") { open(PAGE_ABOUT) }
    }

    // ---------- pages ----------

    private fun appearance(f: Form) {
        f.section("Theme")
        f.choice(
            "Theme",
            listOf(
                "Follow system" to Prefs.THEME_SYSTEM,
                "Light" to Prefs.THEME_LIGHT,
                "Dark" to Prefs.THEME_DARK,
                "AMOLED black" to Prefs.THEME_AMOLED
            ),
            { Prefs.theme },
            { Prefs.theme = it },
            onChanged = {
                NovaApp.applyTheme()
                recreate()
            }
        )

        f.choice(
            "Accent colour",
            listOf(
                "Pink" to 0xFFFF2D55.toInt(), "Red" to 0xFFE62117.toInt(), "Orange" to 0xFFFF6D00.toInt(),
                "Green" to 0xFF00B050.toInt(), "Blue" to 0xFF2979FF.toInt(), "Purple" to 0xFFAA47FF.toInt()
            ),
            { Prefs.accent },
            { Prefs.accent = it },
            onChanged = { restartApp() }
        )
        f.choice(
            "Start screen",
            listOf(
                "Home" to Nav.TAB_HOME, "Subscriptions" to Nav.TAB_SUBS, "Queue" to Nav.TAB_QUEUE,
                "Downloads" to Nav.TAB_DOWNLOADS, "You" to Nav.TAB_LIBRARY
            ),
            { Prefs.startTab },
            { Prefs.startTab = it }
        )

        f.section("Lists")
        f.toggle(
            "Page numbers",
            "Show 1 2 3 \u2026 under the lists. Turn off for endless scrolling",
            { Prefs.pagedFeed },
            { Prefs.pagedFeed = it }
        )
        f.toggle(
            "Compact list",
            "Small thumbnails on the left instead of big cards on Home, search and channels",
            { Prefs.compactFeed },
            { Prefs.compactFeed = it }
        )
        f.toggle("Hide watched videos", "Leave out videos you already watched to the end", { Prefs.hideWatched }, { Prefs.hideWatched = it })
        f.toggle(
            "Thumbnail labels",
            "Show the length and quality on thumbnails",
            { Prefs.thumbBadges },
            { Prefs.thumbBadges = it }
        )

        f.section("Watch page")
        f.toggle(
            "Related videos as cards",
            "Big cards under the player, like the YouTube app. Off for small rows",
            { Prefs.relatedCards },
            { Prefs.relatedCards = it }
        )
        f.toggle(
            "Mini player",
            "Keep the current video one tap away above the tab bar",
            { Prefs.miniPlayer },
            { Prefs.miniPlayer = it }
        )
    }

    private fun playback(f: Form) {
        f.section("Playing")
        f.toggle("Autoplay next", "Keep playing the queue, then related videos", { Prefs.autoplayNext }, { Prefs.autoplayNext = it })
        f.toggle("Resume where you stopped", "Continue a video from its last position", { Prefs.resumePlayback }, { Prefs.resumePlayback = it })
        f.toggle("Background play", "Keep the sound going when you leave the app or lock the screen", { Prefs.backgroundPlay }, { Prefs.backgroundPlay = it })
        f.toggle("Picture-in-picture", "Shrink the video into a small window when you leave the app", { Prefs.pipOnLeave }, { Prefs.pipOnLeave = it })
        f.toggle("Fullscreen on rotate", "Turn the phone sideways to watch fullscreen", { Prefs.autoLandscapeFullscreen }, { Prefs.autoLandscapeFullscreen = it })

        f.section("Speed")
        f.choice(
            "Default speed",
            listOf(
                "0.5x" to 0.5f, "0.75x" to 0.75f, "Normal" to 1f, "1.25x" to 1.25f,
                "1.5x" to 1.5f, "1.75x" to 1.75f, "2x" to 2f
            ),
            { Prefs.defaultSpeed },
            { Prefs.defaultSpeed = it }
        )
        f.toggle("Remember speed", "Use the last speed you picked for the next video", { Prefs.rememberSpeed }, { Prefs.rememberSpeed = it })
        f.toggle("Speeds up to 4x", "Add 3.5x and 4x to the speed menu", { Prefs.extendedSpeeds }, { Prefs.extendedSpeeds = it })

        f.section("Video quality")
        f.choice(
            "On Wi-Fi",
            listOf("Auto" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360, "240p" to 240),
            { Prefs.streamQuality },
            { Prefs.streamQuality = it }
        )
        f.choice(
            "On mobile data",
            listOf("Same as Wi-Fi" to -1, "Auto" to 0, "720p" to 720, "480p" to 480, "360p" to 360, "240p" to 240),
            { Prefs.streamQualityMobile },
            { Prefs.streamQualityMobile = it }
        )
        f.note("The highest quality the player may pick. You can still change it for one video in the player's settings.")
        f.toggle(
            "Remember quality changes",
            "A quality you pick in the player becomes the new default",
            { Prefs.rememberQuality },
            { Prefs.rememberQuality = it }
        )

        f.section("Player buttons")
        f.multi(
            "Buttons in the player",
            listOf(
                Form.Check("Ambient mode", { Prefs.pbAmbient }, { Prefs.pbAmbient = it }),
                Form.Check("Picture-in-picture", { Prefs.pbPip }, { Prefs.pbPip = it }),
                Form.Check("Speed", { Prefs.pbSpeed }, { Prefs.pbSpeed = it }),
                Form.Check("Quality", { Prefs.pbQuality }, { Prefs.pbQuality = it }),
                Form.Check("Loop", { Prefs.pbLoop }, { Prefs.pbLoop = it })
            )
        )
    }

    private fun gestures(f: Form) {
        f.section("Player gestures")
        f.toggle("Gestures", "Turn all swipes and holds on or off", { Prefs.gestures }, { Prefs.gestures = it })
        f.toggle("Swipe for brightness", "Slide up or down on the left half", { Prefs.swipeBrightness }, { Prefs.swipeBrightness = it })
        f.toggle("Swipe for volume", "Slide up or down on the right half", { Prefs.swipeVolume }, { Prefs.swipeVolume = it })
        f.toggle("Swipe to seek", "Slide left or right across the video", { Prefs.swipeSeek }, { Prefs.swipeSeek = it })
        f.choice(
            "Swipe sensitivity",
            listOf("Low (50%)" to 50, "75%" to 75, "Normal (100%)" to 100, "150%" to 150, "High (200%)" to 200),
            { Prefs.swipeSensitivity },
            { Prefs.swipeSensitivity = it }
        )
        f.choice(
            "Double-tap skip",
            listOf("5 seconds" to 5, "10 seconds" to 10, "15 seconds" to 15, "30 seconds" to 30),
            { Prefs.doubleTapSeekSec },
            { Prefs.doubleTapSeekSec = it }
        )

        f.section("Hold and pinch")
        f.toggle("Hold to speed up", "Keep a finger on the video to play faster", { Prefs.holdToSpeed }, { Prefs.holdToSpeed = it })
        f.choice(
            "Hold speed",
            listOf("1.5x" to 1.5f, "2x" to 2f, "3x" to 3f, "4x" to 4f),
            { Prefs.holdSpeed },
            { Prefs.holdSpeed = it }
        )
        f.toggle("Pinch to zoom", "Pinch out to fill the screen, pinch in to fit the whole picture", { Prefs.pinchZoom }, { Prefs.pinchZoom = it })
    }

    private fun ambient(f: Form) {
        f.section("Ambient mode")
        f.toggle("Ambient mode", "Light the page and the black bars with the colours of the video", { Prefs.ambient }, { Prefs.ambient = it })
        f.choice(
            "Strength",
            listOf("Subtle" to 30, "Soft" to 50, "Normal" to 75, "Strong" to 100),
            { Prefs.ambientStrength },
            { Prefs.ambientStrength = it }
        )
        f.choice(
            "Blur",
            listOf("Sharp (keeps the shapes)" to 0, "Light" to 1, "Medium" to 2, "Strong" to 3),
            { Prefs.ambientBlur },
            { Prefs.ambientBlur = it }
        )
        f.choice(
            "Smoothing",
            listOf("Off (follows every change)" to 0, "Low" to 1, "Medium" to 2, "High (very slow glide)" to 3),
            { Prefs.ambientSmooth },
            { Prefs.ambientSmooth = it }
        )
        f.toggle("Dim while scrolling", "Make the light softer while you scroll the page", { Prefs.ambientDimScroll }, { Prefs.ambientDimScroll = it })
        f.choice(
            "Dim time",
            listOf("1 second" to 1000, "1.5 seconds" to 1500, "3 seconds" to 3000, "5 seconds" to 5000),
            { Prefs.ambientDimMs },
            { Prefs.ambientDimMs = it }
        )
        f.toggle("Keep text readable", "Darken the light under the player so titles and descriptions stay clear", { Prefs.ambientReadable }, { Prefs.ambientReadable = it })
        f.toggle("Light the black bars", "Fill the bars around the video (fullscreen, and videos that are not 16:9) with the light", { Prefs.ambientBars }, { Prefs.ambientBars = it })
    }

    private fun downloads(f: Form) {
        f.section("Downloads")
        f.choice(
            "Download quality",
            listOf(
                "Ask every time" to -1, "Best available" to 0, "1080p" to 1080, "720p" to 720,
                "480p" to 480, "360p" to 360, "240p" to 240
            ),
            { Prefs.downloadQuality },
            { Prefs.downloadQuality = it }
        )
        f.toggle("Wi-Fi only", "Wait for Wi-Fi instead of using mobile data", { Prefs.wifiOnlyDownloads }, { Prefs.wifiOnlyDownloads = it })
    }

    private fun privacy(f: Form) {
        f.section("Watch history")
        f.toggle("Keep watch history", null, { Prefs.keepHistory }, { Prefs.keepHistory = it })
        f.action("Clear watch history", null) {
            Dialogs.confirm(this, "Clear watch history?", "This also forgets where you stopped in each video.", "Clear") {
                Library.clearHistory()
                Dialogs.toast(this, "Watch history cleared")
            }
        }
        f.section("Searches")
        f.action("Clear search history", null) {
            Library.clearSearches()
            Dialogs.toast(this, "Search history cleared")
        }
    }

    private fun site(f: Form) {
        f.section("Site")
        val address = f.action("Site address", Prefs.baseUrl) { editAddress() }
        addressView = address
        f.note(
            "If the site does not open, try another address of it here (a mirror), or turn off Private DNS / switch " +
                "your DNS in the phone's network settings. If it asks for a human check, open it once in the browser: " +
                "NovaPlay then reuses the result."
        )
        f.action("Open site in browser", "Solve a human check or sign in once") { BrowserActivity.open(this, Site.home()) }
    }

    private fun about(f: Form) {
        f.section("About")
        f.note("NovaPlay 1.2. Videos are loaded from the site you choose in Settings \u203a Site; NovaPlay itself hosts nothing.")
        f.section("Settings backup")
        f.action("Back up settings", "Copy all your settings as text") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("NovaPlay settings", Prefs.exportAll()))
            Dialogs.toast(this, "Settings copied. Paste them somewhere safe")
        }
        f.action("Restore settings", "Paste settings you copied before") { restoreSettings() }
        f.section("Help")
        f.action("Last crash report", "If the app closed by itself, copy this report and send it on") { showCrash() }
        f.action("Diagnostics", "Check how the site's pages are read; copy the result if something looks wrong") { runDiagnostics() }
    }

    private fun restoreSettings() {
        val box = android.widget.EditText(this)
        box.hint = "Paste the copied settings here"
        box.setTextColor(Ui.text(this))
        box.setHintTextColor(Ui.textDim(this))
        box.minLines = 4
        box.gravity = android.view.Gravity.TOP
        val pad = Ui.dp(this, 20)
        val wrap = LinearLayout(this)
        wrap.setPadding(pad, Ui.dp(this, 8), pad, 0)
        wrap.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
        AlertDialog.Builder(this)
            .setTitle("Restore settings")
            .setView(wrap)
            .setPositiveButton("Restore") { _, _ ->
                val n = Prefs.importAll(box.text.toString())
                if (n < 0) {
                    Dialogs.toast(this, "That is not NovaPlay settings")
                } else {
                    Dialogs.toast(this, "Restored $n settings")
                    NovaApp.applyTheme()
                    restartApp()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Starts the app again from its first screen so every screen is built with the new look. */
    private fun restartApp() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
    }

    private fun showCrash() {
        val report = CrashLog.read()
        if (report == null) {
            Dialogs.toast(this, "No crash has been saved")
            return
        }
        showText("Last crash report", report) { CrashLog.clear() }
    }

    /** A selectable monospace report with a Copy button. [onClear] adds a Clear button. */
    private fun showText(title: String, report: String, onClear: (() -> Unit)? = null) {
        val text = TextView(this)
        text.text = report
        text.typeface = Typeface.MONOSPACE
        text.textSize = 11f
        text.setTextColor(Ui.text(this))
        text.setTextIsSelectable(true)
        val pad = Ui.dp(this, 16)
        text.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this)
        scroll.addView(text)
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("NovaPlay report", report))
                Dialogs.toast(this, "Copied")
            }
            .setNegativeButton("Close", null)
        if (onClear != null) b.setNeutralButton("Clear") { _, _ -> onClear() }
        b.show()
    }

    private fun runDiagnostics() {
        val busy = AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setMessage("Reading the site. This can take a minute or two.")
            .setCancelable(false)
            .create()
        busy.show()
        lifecycleScope.launch {
            val report = try {
                SiteClient.diagnose(listOf(Site.home(), Site.newest(), Site.search("test")))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Diagnostics failed: ${e.message}\n"
            }
            busy.dismiss()
            showText("Diagnostics", deviceLine() + report)
        }
    }

    private fun deviceLine(): String {
        val webview = try {
            if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } else null
        } catch (e: Exception) {
            null
        }
        return "NovaPlay 1.2 | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | WebView ${webview ?: "?"}\n" +
            "site: ${Site.base} | page numbers: ${Prefs.pagedFeed}\n\n"
    }

    private var addressView: TextView? = null

    private fun editAddress() {
        Dialogs.prompt(this, "Site address", Prefs.DEFAULT_BASE_URL, Prefs.baseUrl, "Save") { text ->
            val fixed = normalize(text)
            if (fixed == null) {
                Dialogs.toast(this, "That does not look like a web address")
            } else {
                Prefs.baseUrl = fixed
                addressView?.text = fixed
                Dialogs.toast(this, "Saved")
            }
        }
    }

    /** "xhamster.desi/some/page" -> "https://xhamster.desi"; null when it cannot be an address. */
    private fun normalize(input: String): String? {
        var t = input.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://$t"
        val host = Site.hostOf(t)
        if (host.isEmpty() || !host.contains('.') || host.contains(' ')) return null
        return t.substringBefore("://") + "://" + host
    }

    companion object {
        const val EXTRA_PAGE = "settings_page"
        const val PAGE_APPEARANCE = "appearance"
        const val PAGE_PLAYBACK = "playback"
        const val PAGE_GESTURES = "gestures"
        const val PAGE_AMBIENT = "ambient"
        const val PAGE_DOWNLOADS = "downloads"
        const val PAGE_PRIVACY = "privacy"
        const val PAGE_SITE = "site"
        const val PAGE_ABOUT = "about"
    }
}
