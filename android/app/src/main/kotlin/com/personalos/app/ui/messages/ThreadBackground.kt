package com.personalos.app.ui.messages

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.personalos.app.ui.common.RadarColors

/** The thread's canvas: plain paper, or the low-opacity dot grid. */
enum class ThreadBackground { PLAIN, DOTTED }

/** One app-wide choice, so every thread shares it. */
class ThreadBackgroundStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("thread_bg", Context.MODE_PRIVATE)

    fun get(): ThreadBackground {
        val raw = prefs.getString(KEY, ThreadBackground.DOTTED.name) ?: return ThreadBackground.DOTTED
        return runCatching { ThreadBackground.valueOf(raw) }.getOrDefault(ThreadBackground.DOTTED)
    }

    fun set(value: ThreadBackground) {
        prefs.edit().putString(KEY, value.name).apply()
    }

    private companion object {
        const val KEY = "mode"
    }
}

/** Per-conversation mute, keyed by sender. */
class MuteStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("thread_mute", Context.MODE_PRIVATE)

    fun isMuted(sender: String): Boolean = prefs.getBoolean(sender, false)

    fun setMuted(
        sender: String,
        muted: Boolean,
    ) {
        prefs.edit().putBoolean(sender, muted).apply()
    }
}

/** A quiet dot grid: 1.2dp dots, 14dp apart, 5% ink. */
@Composable
fun DottedBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val step = 14.dp.toPx()
        val radius = 1.2.dp.toPx()
        val color = RadarColors.ink.copy(alpha = 0.05f)
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawCircle(color = color, radius = radius, center = Offset(x, y))
                x += step
            }
            y += step
        }
    }
}
