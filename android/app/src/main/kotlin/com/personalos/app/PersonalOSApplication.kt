package com.personalos.app

import android.app.Application
import com.personalos.app.ui.theme.RadarFonts

class PersonalOSApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Resolve the bundled Noto families once, before any screen composes.
        // Missing faces fall back to the platform family, so this is safe while
        // the font files are still being added.
        RadarFonts.load(this)
    }
}
