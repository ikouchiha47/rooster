package com.personalos.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.notify.Severity
import com.personalos.app.core.notify.visibleNotification
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.delay

/**
 * The one notification host. Mounted at the root ([com.personalos.app.MainActivity])
 * rather than inside a tile scaffold, so **every** screen shows a notification —
 * News, Weather and Money used to have one, Forecast started a sync with no bar,
 * and the rest had nowhere for it to live.
 *
 * It decides only *which* notification to show (the loudest, via
 * [visibleNotification]) and how it looks — a centred line on the severity band;
 * the content is whatever was posted. This is the base component the per-screen
 * banners were groping toward.
 */
@Composable
fun NotificationHost(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val active by container.notifications.active.collectAsStateWithLifecycle()
    val notification = visibleNotification(active) ?: return

    // A ttl notification (a failed sync, say) clears itself.
    LaunchedEffect(notification.id, notification.at) {
        val ttl = notification.ttlMs ?: return@LaunchedEffect
        delay(ttl)
        container.notifications.dismiss(notification.id)
    }

    val background = severityColor(notification.severity)
    val content = headerContentColor(background)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (notification.body ?: notification.title).uppercase(),
            style = RadarType.micro,
            color = content,
            maxLines = 1,
        )
    }
}

/** Severity decides the band; the content colour follows from it, one owner. */
private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.INFO -> RadarColors.ink
        Severity.SUCCESS -> CategoryColors.Teal
        Severity.WARNING -> CategoryColors.Rust
        Severity.ERROR -> CategoryColors.Vermilion
    }
