package com.personalos.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.data.RuleEntity
import com.personalos.app.ui.common.BackButton
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphActionButton
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.StatusBarIconsFor
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

/**
 * Rules list: every rule, its enabled state, and clearly marked bundled rows.
 *
 * The screen is a view over [RuleRepository.observe]; it never holds its own
 * copy of the list (CODE-DESIGN-GUIDELINES.md §1).
 */
@Composable
fun RulesScreen(
    onNewRule: () -> Unit,
    onEditRule: (RuleEntity) -> Unit,
    onInspectRule: (RuleEntity) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val rules by container.ruleRepository.observe().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(Filter.ALL) }

    val filtered =
        remember(rules, filter) {
            when (filter) {
                Filter.ALL -> rules
                Filter.ACTIVE -> rules.filter { it.enabled }
                Filter.PAUSED -> rules.filter { !it.enabled }
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper),
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
            if (onBack != null) {
                BackButton(onBack = onBack)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Rules",
                    style = RadarType.serifH3,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${rules.size} configured · ${rules.count { it.enabled }} active".uppercase(),
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                    maxLines = 1,
                )
            }
            GlyphActionButton(glyph = Glyph.Plus, onClick = onNewRule)
        }
        HardRule()

        FilterTabs(
            selected = filter,
            counts =
                mapOf(
                    Filter.ALL to rules.size,
                    Filter.ACTIVE to rules.count { it.enabled },
                    Filter.PAUSED to rules.count { !it.enabled },
                ),
            onSelect = { filter = it },
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    onToggle = { enabled ->
                        if (!rule.seeded) {
                            scope.launch {
                                runCatching { container.ruleRepository.setEnabled(rule.id, enabled) }
                            }
                        }
                    },
                    onEdit = { onEditRule(rule) },
                    onInspect = { onInspectRule(rule) },
                    onDelete = {
                        if (!rule.seeded) {
                            scope.launch {
                                runCatching { container.ruleRepository.delete(rule.id) }
                            }
                        }
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FlatButton(
                        text = "New rule",
                        onClick = onNewRule,
                        modifier = Modifier.weight(1f),
                        solid = true,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private enum class Filter { ALL, ACTIVE, PAUSED }

@Composable
private fun FilterTabs(
    selected: Filter,
    counts: Map<Filter, Int>,
    onSelect: (Filter) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(RadarColors.paper2)) {
        Row(Modifier.fillMaxWidth()) {
            Filter.entries.forEach { filter ->
                val isSelected = filter == selected
                Row(
                    modifier =
                        Modifier
                            .clickable { onSelect(filter) }
                            .background(if (isSelected) RadarColors.ink else Color.Transparent)
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = filter.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = RadarType.label,
                        color = if (isSelected) RadarColors.paper2 else RadarColors.ink2,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = (counts[filter] ?: 0).toString(),
                        style = RadarType.microPlain,
                        color = if (isSelected) RadarColors.paper4 else RadarColors.ink3,
                        maxLines = 1,
                    )
                }
                Box(Modifier.width(1.dp).height(26.dp).background(RadarColors.ruleSoft))
            }
        }
        HardRule()
    }
}

/**
 * Whether a rule should open in read-only mode.
 *
 * Derived from [seeded], not from user action, so the decision is testable
 * without Compose and every bundled rule is inspectable.
 */
fun isRuleReadOnly(seeded: Boolean): Boolean = seeded

@Composable
private fun RuleRow(
    rule: RuleEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onInspect: () -> Unit,
    onDelete: () -> Unit,
) {
    val spineColor = remember(rule.conditionJson) { ruleSpineColor(rule.conditionJson) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clickable(
                        onClickLabel = if (rule.seeded) "View rule" else "Edit rule",
                        onClick = { if (rule.seeded) onInspect() else onEdit() },
                    ),
            verticalAlignment = Alignment.Top,
        ) {
            CategorySpine(color = spineColor, modifier = Modifier.padding(vertical = 0.dp))
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = RadarType.serifTitle,
                        color = if (rule.enabled) RadarColors.ink else RadarColors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (rule.seeded) {
                        Spacer(Modifier.width(4.dp))
                        Chip(text = "Bundled", color = RadarColors.ink3)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = conditionSummary(rule.conditionJson),
                    style = RadarType.microPlain,
                    color = RadarColors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val action =
                        runCatching {
                            com.personalos.app.core.rules.ActionJson
                                .parse(rule.actionJson)
                        }.getOrNull()
                    Text(
                        text =
                            buildString {
                                append("DELIVERY ")
                                append(if (action?.delivery == com.personalos.app.core.rules.Delivery.PUSH) "push" else "none")
                                append(" · POSITION ")
                                append(action?.position ?: 0)
                                if (!rule.enabled) append(" · PAUSED")
                            },
                        style = RadarType.micro,
                        color = RadarColors.ink3,
                        maxLines = 1,
                    )
                }
                if (!rule.seeded) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlatButton(text = "Edit", onClick = onEdit, solid = false)
                        FlatButton(text = "Delete", onClick = onDelete, solid = false)
                    }
                }
            }
            Column(
                modifier =
                    Modifier
                        .padding(end = 8.dp, top = 6.dp)
                        .then(
                            if (!rule.seeded) {
                                Modifier.clickable(
                                    onClickLabel = if (rule.enabled) "Disable rule" else "Enable rule",
                                    onClick = { onToggle(!rule.enabled) },
                                )
                            } else {
                                Modifier
                            },
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ToggleSwitch(
                    on = rule.enabled,
                    enabled = !rule.seeded,
                )
            }
        }
        SoftRule()
    }
}

/** Chooses a spine colour from the rule's condition, or cream if none. */
private fun ruleSpineColor(conditionJson: String): Color =
    runCatching {
        val condition =
            com.personalos.app.core.rules.ConditionJson
                .parse(conditionJson)
        val tag = findFirstTag(condition)
        when (tag?.lowercase()) {
            com.personalos.app.core.tag.Tags.FINANCE -> CategoryColors.Teal
            com.personalos.app.core.tag.Tags.TECH -> CategoryColors.Plum
            com.personalos.app.core.tag.Tags.TRAVEL -> CategoryColors.Mustard
            com.personalos.app.core.tag.Tags.WEATHER -> CategoryColors.Chartreuse
            com.personalos.app.core.tag.Tags.GAMES -> CategoryColors.Periwinkle
            com.personalos.app.core.tag.Tags.PAPER -> CategoryColors.Indigo
            else -> RadarColors.paper4
        }
    }.getOrDefault(RadarColors.paper4)

private fun findFirstTag(condition: com.personalos.app.core.rules.Condition): String? =
    when (condition) {
        is com.personalos.app.core.rules.Condition.All -> condition.conditions.firstNotNullOfOrNull { findFirstTag(it) }
        is com.personalos.app.core.rules.Condition.Any -> condition.conditions.firstNotNullOfOrNull { findFirstTag(it) }
        is com.personalos.app.core.rules.Condition.Subject -> condition.tag
        is com.personalos.app.core.rules.Condition.Nature -> condition.tag
        else -> null
    }

@Composable
private fun ToggleSwitch(
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackColor =
        when {
            !enabled -> RadarColors.paper4
            on -> RadarColors.ink
            else -> RadarColors.ruleSoft
        }
    val thumbColor =
        when {
            !enabled -> RadarColors.paper3
            else -> RadarColors.paper2
        }
    Box(
        modifier = modifier,
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 32.dp, height = 18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(trackColor)
                    .padding(2.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(thumbColor),
            )
        }
    }
}
