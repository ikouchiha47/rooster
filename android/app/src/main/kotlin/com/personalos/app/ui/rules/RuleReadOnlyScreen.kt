package com.personalos.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.Delivery
import com.personalos.app.data.RuleEntity
import com.personalos.app.ui.common.BackButton
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.StatusBarIconsFor
import com.personalos.app.ui.theme.RadarType

/**
 * Read-only inspection of a bundled rule.
 *
 * Shows every stored field so the owner can see what a seed does, without
 * offering any write path. Seeded rows are locked in the repository; the UI
 * mirrors that by never presenting a Save affordance.
 */
@Composable
fun RuleReadOnlyScreen(
    ruleId: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val rules by container.ruleRepository.observe().collectAsState(initial = emptyList())
    val rule = rules.find { it.id == ruleId }

    Column(
        modifier = Modifier.fillMaxSize().background(RadarColors.paper),
    ) {
        StatusBarIconsFor(RadarColors.paper2)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack = onBack)
            Spacer(Modifier.width(8.dp))
            Text(
                text = rule?.name ?: "Rule",
                style = RadarType.serifH3,
                color = RadarColors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        HardRule()

        if (rule != null) {
            RuleReadOnlyBody(rule = rule)
        }
    }
}

@Composable
private fun RuleReadOnlyBody(rule: RuleEntity) {
    val action = runCatching { ActionJson.parse(rule.actionJson) }.getOrNull()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.paper2)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Bundled rules ship with the app and are replaced on upgrade, so they can't be edited.",
                style = RadarType.body,
                color = RadarColors.ink2,
            )
        }
        HardRule()

        MetadataRow(label = "ID", value = rule.id)
        MetadataRow(label = "Enabled", value = if (rule.enabled) "Yes" else "No")
        MetadataRow(
            label = "Delivery",
            value = if (action?.delivery == Delivery.PUSH) "push" else "none",
        )
        MetadataRow(label = "Position", value = (action?.position ?: 0).toString())

        HardRule()

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(text = "Condition".uppercase(), style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(2.dp))
            Text(
                text = conditionSummary(rule.conditionJson),
                style = RadarType.serifTitle,
                color = RadarColors.ink,
            )
        }
        HardRule()

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(text = "Condition JSON".uppercase(), style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(2.dp))
            Text(
                text = rule.conditionJson,
                style = RadarType.monoSmall,
                color = RadarColors.ink,
            )
        }
        HardRule()

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(text = "Action JSON".uppercase(), style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(2.dp))
            Text(
                text = rule.actionJson,
                style = RadarType.monoSmall,
                color = RadarColors.ink,
            )
        }
        HardRule()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = RadarType.micro,
            color = RadarColors.ink3,
            modifier = Modifier.width(80.dp),
        )
        Text(text = value, style = RadarType.body, color = RadarColors.ink)
    }
}
