package com.novaplay.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.novaplay.app.R
import com.novaplay.app.data.Prefs

/** Applies the AMOLED variant of the theme before the window is created. */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Prefs.theme == Prefs.THEME_AMOLED) setTheme(R.style.Theme_NovaPlay_Amoled)
        super.onCreate(savedInstanceState)
    }
}
