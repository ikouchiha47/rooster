package com.personalos.app.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.personalos.app.core.AppContainer

/**
 * Provides the composition root to the UI. Screens depend on the provider
 * *interfaces* via this container, never on a concrete data source.
 */
val LocalAppContainer =
    staticCompositionLocalOf<AppContainer> {
        error("AppContainer was not provided. Wrap the content in CompositionLocalProvider(LocalAppContainer provides ...).")
    }
