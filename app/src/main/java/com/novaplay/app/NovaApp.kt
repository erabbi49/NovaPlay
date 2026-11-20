package com.novaplay.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.novaplay.app.adblock.FilterLists
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.download.DownloadCenter
import com.novaplay.app.download.DownloadService
import com.novaplay.app.player.Playback
import com.novaplay.app.web.SiteClient

/** Starts every long-lived part of the app, in the order they depend on each other. */
class NovaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Prefs.init(this)
        applyTheme()
        Library.init(this)
        FilterLists.init(this)
        SiteClient.init(this)
        Playback.init(this)
        DownloadCenter.init(this)
        DownloadService.ensureChannels(this)
    }

    companion object {
        /** Maps the theme setting onto the day/night mode. AMOLED is a dark theme with true black on top. */
        fun applyTheme() {
            val mode = when (Prefs.theme) {
                Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.THEME_DARK, Prefs.THEME_AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
