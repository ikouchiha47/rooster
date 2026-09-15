package com.personalos.app

import android.app.Application
import com.personalos.app.data.ThemeRepository
import com.personalos.app.data.cache.PrefsStringCache
import com.personalos.app.ui.theme.RadarFonts

class PersonalOSApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Resolve the bundled faces and the stored theme once, before any
        // screen composes, so the first frame already uses the right pairing.
        // The prefs store is the source of truth across restarts; RadarFonts
        // holds the runtime projection of it. Missing faces fall back to the
        // platform family, so this is safe while the font files settle.
        val stored = ThemeRepository(PrefsStringCache(this)).load()
        RadarFonts.load(this, stored)
    }
}
