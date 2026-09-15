package com.personalos.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.rememberNavController
import com.personalos.app.core.AppContainer
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.SmsSource
import com.personalos.app.data.work.SyncScheduler
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.navigation.AppNavHost
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.navigation.navigateTo
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.PersonalOSTheme
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var smsSource: SmsSource
    private var hasStartedObserving = false

    var smsPermissionGranted by mutableStateOf(false)
        private set

    private val requestReadSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            smsPermissionGranted = granted
            if (granted) startSmsObserving()
        }

    /**
     * Present location for the Weather tile.
     *
     * Nothing depends on the result here: the provider checks the grant itself when
     * the screen asks for a fix, so this only has to ask once, at launch.
     */
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge before setContent
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val container = AppContainer(this)
        val database = AppDatabase.getInstance(this)
        smsSource = SmsSource(this, database, container.tagWriter, container.mentionWriter)

        // Periodic background ingest; WorkManager persists this across reboots.
        SyncScheduler.schedule(this)

        // Launch catch-up runs as a persisted worker, not in this scope: the
        // chain (retag, seeds, mention backfill) is store-wide CPU work that
        // ANR'd the app when it ran Main-bound here. See BackfillWorker.
        SyncScheduler.enqueueBackfillOnce(this)

        smsPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PersonalOSTheme {
                    PersonalOSApp()
                }
            }
        }

        if (smsPermissionGranted) {
            startSmsObserving()
        } else {
            requestReadSmsPermission.launch(Manifest.permission.READ_SMS)
        }

        // Coarse is enough for a forecast; asked once, at launch.
        val hasLocation =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun startSmsObserving() {
        if (!hasStartedObserving) {
            hasStartedObserving = true
            smsSource.startObserving()
        }
    }
}

@Composable
fun PersonalOSApp() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf(0) }

    val tabs =
        listOf(
            TabItem("Home", Glyph.Home),
            TabItem("Services", Glyph.Grid),
            TabItem("Messages", Glyph.Mail),
            TabItem("Wallet", Glyph.Wallet),
            TabItem("Me", Glyph.Person),
        )

    val destinations =
        listOf(
            Destination.Home,
            Destination.Services,
            Destination.Messages,
            Destination.Wallet,
            Destination.Me,
        )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Single insets application: status bar / cutout on top,
        // navigation-bar-or-gesture area at the bottom, and IME above it.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                AppNavHost(navController)
            }

            BottomNavigationBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                onTabSelected = { index ->
                    selectedTab = index
                    navigateTo(navController, destinations[index])
                },
            )
        }
    }
}

data class TabItem(
    val label: String,
    val glyph: Glyph,
)

@Composable
fun BottomNavigationBar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // hard ink rule above the bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RadarColors.ink),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(RadarColors.paper3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedIndex
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isSelected) RadarColors.paper2 else Color.Transparent)
                            .clickable { onTabSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 3dp vermilion indicator on the active tab
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (isSelected) CategoryColors.Vermilion else Color.Transparent),
                    )
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        GlyphIcon(
                            glyph = tab.glyph,
                            tint = if (isSelected) RadarColors.ink else RadarColors.ink3,
                            size = 19.dp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label.uppercase(),
                            style = RadarType.navLabel,
                            color = if (isSelected) RadarColors.ink else RadarColors.ink3,
                        )
                    }
                }
                if (index != tabs.lastIndex) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(RadarColors.ruleSoft),
                    )
                }
            }
        }
    }
}
