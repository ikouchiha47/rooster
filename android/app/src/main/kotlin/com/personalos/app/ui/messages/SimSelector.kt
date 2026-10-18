package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personalos.app.core.sms.Sim
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.theme.RadarType

/**
 * The SIM picker: one chip per active SIM, the chosen one on ink. Renders nothing
 * below two SIMs, so single-SIM devices never see it.
 */
@Composable
fun SimSelector(
    sims: List<Sim>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sims.size < 2) return
    Row(modifier.horizontalScroll(rememberScrollState())) {
        sims.forEach { sim ->
            val on = selected == sim.subscriptionId || (selected == null && sim == sims.first())
            Text(
                text = sim.label.uppercase(),
                style = RadarType.micro,
                color = if (on) RadarColors.paper2 else RadarColors.ink2,
                modifier =
                    Modifier
                        .clickable { onSelect(sim.subscriptionId) }
                        .background(if (on) RadarColors.ink else RadarColors.paper)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
