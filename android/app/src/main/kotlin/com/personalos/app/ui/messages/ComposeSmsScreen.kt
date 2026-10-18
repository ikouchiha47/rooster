package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.core.Chars
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Compose one message to N recipients.
 *
 * Send is a **fan-out**: one [com.personalos.app.core.sms.SmsSender.send] per
 * recipient, each with its own status. A recipient that fails does not abort the
 * others and does not hide one that succeeded — the success stands. Send is off
 * until there is a body and at least one recipient.
 *
 * Recording what was sent is `SmsSource`'s job, not this screen's (one fact, one
 * owner), so the screen calls the send seam and reports the result; the next
 * ingest records it.
 */
@Composable
fun ComposeSmsScreen(
    recipients: List<String>,
    mode: RecipientMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var sims by remember { mutableStateOf<List<com.personalos.app.core.sms.Sim>>(emptyList()) }
    var selectedSim by remember { mutableStateOf(container.simPreference.selected()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        sims = runCatching { container.sims.active() }.getOrDefault(emptyList())
    }

    var to by remember { mutableStateOf(recipients) }
    var body by remember { mutableStateOf("") }
    var numberDraft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var statuses by remember { mutableStateOf<Map<String, SendStatus>>(emptyMap()) }

    val canSend = body.isNotBlank() && to.isNotEmpty() && !sending

    fun send() {
        if (!canSend) return
        val trimmed = body.trim()
        val targets = to
        sending = true
        statuses = targets.associateWith { SendStatus.Pending }
        scope.launch {
            // Fan out concurrently: each send suspends on its own platform result,
            // so one slow or failed recipient never holds up the rest.
            coroutineScope {
                targets
                    .map { recipient ->
                        async {
                            val status =
                                runCatching { container.smsSender.send(recipient, trimmed, selectedSim) }
                                    .fold(
                                        onSuccess = { SendStatus.of(it) },
                                        onFailure = { SendStatus.Failed("the send failed") },
                                    )
                            statuses = statuses + (recipient to status)
                        }
                    }.awaitAll()
            }
            sending = false
        }
    }

    Column(modifier = modifier.fillMaxSize().background(RadarColors.paper2)) {
        RadarHeader(
            title = "New message",
            accent = RadarColors.ink,
            onBack = onBack,
            trailing = {
                HeaderMeta(
                    "${mode.label} ${Chars.MIDDLE_DOT} ${to.size} recipients",
                    Modifier.padding(end = 8.dp),
                )
            },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(text = "TO", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(3.dp))
            if (to.isEmpty()) {
                Text(
                    text = "No recipients. Remove none, or go back and pick contacts.",
                    style = RadarType.small,
                    color = RadarColors.ink3,
                )
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    to.forEach { number ->
                        RecipientChip(
                            number = number,
                            enabled = !sending,
                            onRemove = { to = to - number },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    hint = "Add a number",
                    query = numberDraft,
                    onQueryChange = { numberDraft = it },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "ADD",
                    style = RadarType.label,
                    color = if (numberDraft.isBlank()) RadarColors.ink3 else RadarColors.ink,
                    modifier =
                        Modifier
                            .clickable {
                                val n = numberDraft.trim()
                                if (n.isNotEmpty() && n !in to) {
                                    to = to + n
                                    numberDraft = ""
                                }
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            if (sims.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(text = "SIM", style = RadarType.micro, color = RadarColors.ink3)
                Spacer(Modifier.height(3.dp))
                SimSelector(
                    sims = sims,
                    selected = selectedSim,
                    onSelect = {
                        selectedSim = it
                        container.simPreference.select(it)
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(text = "MODE", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(3.dp))
            ModeRow(mode)

            Spacer(Modifier.height(8.dp))
            Text(text = "MESSAGE", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(3.dp))
            MessageField(value = body, onChange = { body = it })

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatButton(
                    text = if (sending) "SENDING" else "SEND",
                    onClick = { send() },
                    enabled = canSend,
                    solid = true,
                )
                Spacer(Modifier.width(8.dp))
                if (statuses.isNotEmpty()) {
                    val sent = statuses.values.count { it == SendStatus.Sent }
                    val failed = statuses.values.count { it is SendStatus.Failed }
                    Text(
                        text = "$sent sent ${Chars.MIDDLE_DOT} $failed failed",
                        style = RadarType.microPlain,
                        color = RadarColors.ink2,
                    )
                }
            }
        }
        SoftRule()

        if (statuses.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                statuses.forEach { (number, status) ->
                    RecipientStatusRow(number = number, status = status)
                }
            }
        }
    }
}

/** One removable recipient: a bordered chip that drops the number when tapped. */
@Composable
private fun RecipientChip(
    number: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable(enabled = enabled, onClickLabel = "Remove recipient") { onRemove() }
                .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number,
            style = RadarType.microPlain,
            color = RadarColors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(5.dp))
        GlyphIcon(Glyph.Close, RadarColors.ink2, 12.dp)
    }
}

/**
 * The addressing mode, shown as data rather than a hidden default. `SMS` is the
 * live choice; `Group MMS` is drawn disabled and annotated, so the later
 * behaviour is visible now and adding it is a change here, not a rebuild.
 */
@Composable
private fun ModeRow(mode: RecipientMode) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecipientMode.entries.forEach { candidate ->
            val selected = candidate == mode
            val enabled = candidate.available
            Text(
                text = candidate.label,
                style = RadarType.micro,
                color =
                    when {
                        !enabled -> RadarColors.ink3
                        selected -> RadarColors.paper
                        else -> RadarColors.ink
                    },
                modifier =
                    Modifier
                        .padding(end = 6.dp)
                        .background(if (selected) RadarColors.ink else RadarColors.paper, RoundedCornerShape(2.dp))
                        .border(1.dp, if (enabled) RadarColors.ink else RadarColors.ruleSoft, RoundedCornerShape(2.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        if (!RecipientMode.GroupMms.available) {
            Text(
                text = "Group MMS is not available yet.",
                style = RadarType.microPlain,
                color = RadarColors.ink3,
            )
        }
    }
}

/** The message body, in the app's own field idiom but allowed to run to lines. */
@Composable
private fun MessageField(
    value: String,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper, RoundedCornerShape(2.dp))
            .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
            .padding(horizontal = 7.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = "Message",
                style = RadarType.small,
                color = RadarColors.ink3,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = RadarType.small.copy(color = RadarColors.ink),
            cursorBrush = SolidColor(RadarColors.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        )
    }
}

/** One recipient's outcome: pending, sent, or failed with the plain reason. */
@Composable
private fun RecipientStatusRow(
    number: String,
    status: SendStatus,
) {
    val color =
        when (status) {
            SendStatus.Pending -> RadarColors.ink3
            SendStatus.Sent -> CategoryColors.Teal
            is SendStatus.Failed -> CategoryColors.Vermilion
        }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = number,
                style = RadarType.monoSmall,
                color = RadarColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status.label,
                style = RadarType.microPlain,
                color = color,
                maxLines = 1,
            )
        }
        HardRule()
    }
}
