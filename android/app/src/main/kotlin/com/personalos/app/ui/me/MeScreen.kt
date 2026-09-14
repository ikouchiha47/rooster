package com.personalos.app.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.personalos.app.ui.common.RadarAppBar
import com.personalos.app.ui.common.RadarColors

// Blank placeholder screen: module app bar only, empty body.
@Composable
fun MeScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = RadarColors.paper2,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RadarColors.paper2),
        ) {
            RadarAppBar(title = "Me")
        }
    }
}
