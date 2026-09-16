package com.personalos.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.mention.MentionKind
import com.personalos.app.core.rules.FieldNames
import com.personalos.app.core.rules.FieldOp
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.TextTarget
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.core.tag.TagGroups
import com.personalos.app.data.RulePreview
import com.personalos.app.data.SourceEntity
import com.personalos.app.data.UnavailableReason
import com.personalos.app.ui.common.BackButton
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphActionButton
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.StatusBarIconsFor
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.sourceDisplayName
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rule builder: compose a condition and an action, with a live dry-run preview.
 *
 * Delivery (push/none) and surfacing (position) are separate sections so the
 * two axes are never conflated (ADR 0003 §9).
 */
@Composable
fun RuleBuilderScreen(
    ruleId: String?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val rules by container.ruleRepository.observe().collectAsState(initial = emptyList())
    val rule = rules.find { it.id == ruleId }

    val sourceEntities by container.sourceRepository.observe().collectAsState(initial = emptyList())
    val sourceChoices = remember(sourceEntities) { sourceChoices(sourceEntities) }

    var draft by remember(rule) {
        mutableStateOf(
            rule?.let { RuleDraft.fromRule(it.name, it.conditionJson, it.actionJson) }
                ?: RuleDraft.empty(),
        )
    }

    var preview by remember { mutableStateOf<RulePreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    val canSave = draft.isValid() && draft.name.isNotBlank()

    LaunchedEffect(draft) {
        delay(300)
        if (draft.isValid()) {
            previewLoading = true
            preview =
                runCatching {
                    container.rulePreview.preview(draft.toConditionJson())
                }.getOrElse {
                    RulePreview.Unavailable(UnavailableReason.UNKNOWN, it.message)
                }
            previewLoading = false
        } else {
            preview = null
        }
    }

    Column(
        modifier =
            Modifier
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
            BackButton(onBack = onBack)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (rule == null) "New rule" else "Edit rule",
                style = RadarType.serifH3,
                color = RadarColors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            GlyphActionButton(
                glyph = Glyph.Forward,
                onClick = {
                    if (!canSave) return@GlyphActionButton
                    scope.launch {
                        runCatching {
                            if (rule == null) {
                                container.ruleRepository.create(
                                    name = draft.name,
                                    conditionJson = draft.toConditionJson(),
                                    actionJson = draft.toActionJson(),
                                )
                            } else {
                                container.ruleRepository.update(
                                    id = rule.id,
                                    name = draft.name,
                                    conditionJson = draft.toConditionJson(),
                                    actionJson = draft.toActionJson(),
                                )
                            }
                        }.onSuccess { onSaved() }
                    }
                },
            )
        }
        HardRule()

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            NameField(
                value = draft.name,
                onChange = { draft = draft.copy(name = it) },
            )

            SectionHeader(
                title = "Match when",
                accent = CategoryColors.Teal,
                note = "${draft.predicates.size} of 10",
            )

            CompositionSelector(
                composition = draft.composition,
                onChange = { draft = draft.copy(composition = it) },
            )

            draft.predicates.forEachIndexed { index, predicate ->
                PredicateRow(
                    predicate = predicate,
                    sourceChoices = sourceChoices,
                    onChange = { draft = draft.copy(predicates = draft.predicates.toMutableList().apply { set(index, it) }) },
                    onRemove = { draft = draft.copy(predicates = draft.predicates.toMutableList().apply { removeAt(index) }) },
                )
                if (index != draft.predicates.lastIndex) {
                    CompositionLabel(draft.composition)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                FlatButton(
                    text = "Add condition",
                    onClick = {
                        draft = draft.copy(predicates = draft.predicates + PredicateDraft.Subject(""))
                    },
                    solid = false,
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(title = "Deliver", accent = CategoryColors.Vermilion, note = "interruption")
            DeliverySection(
                push = draft.push,
                onPushChange = { draft = draft.copy(push = it) },
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader(title = "Surfacing", accent = CategoryColors.Indigo, note = "read-time emphasis")
            SurfacingSection(
                position = draft.position,
                onPositionChange = { draft = draft.copy(position = it) },
            )

            Spacer(Modifier.height(8.dp))
            PreviewSection(
                preview = preview,
                loading = previewLoading,
                draftValid = draft.isValid(),
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlatButton(
                    text = if (rule == null) "Save rule" else "Update rule",
                    onClick = {
                        scope.launch {
                            runCatching {
                                if (rule == null) {
                                    container.ruleRepository.create(
                                        name = draft.name,
                                        conditionJson = draft.toConditionJson(),
                                        actionJson = draft.toActionJson(),
                                    )
                                } else {
                                    container.ruleRepository.update(
                                        id = rule.id,
                                        name = draft.name,
                                        conditionJson = draft.toConditionJson(),
                                        actionJson = draft.toActionJson(),
                                    )
                                }
                            }.onSuccess { onSaved() }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    solid = true,
                    enabled = canSave,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NameField(
    value: String,
    onChange: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper2)
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(text = "Name".uppercase(), style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = RadarType.serifTitle.copy(color = RadarColors.ink),
            cursorBrush = SolidColor(RadarColors.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    HardRule()
}

@Composable
private fun CompositionSelector(
    composition: RuleDraft.Composition,
    onChange: (RuleDraft.Composition) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionChip(
            label = "AND",
            selected = composition == RuleDraft.Composition.ALL,
            onClick = { onChange(RuleDraft.Composition.ALL) },
        )
        CompositionChip(
            label = "OR",
            selected = composition == RuleDraft.Composition.ANY,
            onClick = { onChange(RuleDraft.Composition.ANY) },
        )
        Text(
            text = if (composition == RuleDraft.Composition.ALL) "all must hold" else "any may hold",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
        )
    }
}

@Composable
private fun CompositionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .background(if (selected) RadarColors.ink else RadarColors.paper3, RoundedCornerShape(2.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = RadarType.labelMicro,
            color = if (selected) RadarColors.paper2 else RadarColors.ink2,
        )
    }
}

@Composable
private fun CompositionLabel(composition: RuleDraft.Composition) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(16.dp).height(1.dp).background(RadarColors.ruleSoft))
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (composition == RuleDraft.Composition.ALL) "and" else "or",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).height(1.dp).background(RadarColors.ruleSoft))
    }
}

@Composable
private fun PredicateRow(
    predicate: PredicateDraft,
    sourceChoices: List<SourceChoice>,
    onChange: (PredicateDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .background(RadarColors.paper2, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ruleSoft, RoundedCornerShape(2.dp))
                .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeDropdown(
                current = predicate.typeLabel(),
                options = predicateTypeOptions(),
                onSelect = { type ->
                    val defaultSource = sourceChoices.firstOrNull()?.source ?: ""
                    onChange(createDefaultPredicate(type, defaultSource))
                },
                modifier = Modifier.width(100.dp),
            )
            Spacer(Modifier.width(4.dp))
            when (predicate) {
                is PredicateDraft.Subject ->
                    TagDropdown(
                        current = predicate.tag,
                        options = TagGroups.SUBJECTS.toList(),
                        onSelect = { onChange(PredicateDraft.Subject(it)) },
                    )

                is PredicateDraft.Nature ->
                    TagDropdown(
                        current = predicate.tag,
                        options = TagGroups.NATURES.toList(),
                        onSelect = { onChange(PredicateDraft.Nature(it)) },
                    )

                is PredicateDraft.Marker ->
                    Text(text = "is news", style = RadarType.body, color = RadarColors.ink2)

                is PredicateDraft.Mention -> {
                    // Mention kind is closed: only place and party exist today (MentionKind).
                    // A free-text kind would silently never match because no extractor produces it.
                    KindDropdown(
                        current = predicate.kind,
                        onSelect = { onChange(predicate.copy(kind = it)) },
                    )
                    Spacer(Modifier.width(4.dp))
                    // Mention value is free text: places and parties are not exposed through
                    // the container, so a picker would need new plumbing. The kind restriction
                    // alone prevents the "silently never matches" bug for the predicate type.
                    SmallTextField(
                        value = predicate.value,
                        hint = "value",
                        onChange = { onChange(predicate.copy(value = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                is PredicateDraft.Source -> {
                    // Source is closed: a source id no producer uses silently never matches.
                    // The choices are the real event sources (SMS, catalog feeds, user sources).
                    SourceDropdown(
                        current = predicate.sourceId,
                        choices = sourceChoices,
                        onSelect = { onChange(predicate.copy(sourceId = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                is PredicateDraft.Field -> {
                    // Field name is closed: only names a producer writes can match anything.
                    // A name outside FieldNames.SUPPLIED silently never matches (ADR 0003 §13).
                    FieldNameDropdown(
                        current = predicate.name,
                        onSelect = { onChange(predicate.copy(name = it)) },
                    )
                    Spacer(Modifier.width(4.dp))
                    OpDropdown(
                        current = predicate.op,
                        onSelect = { onChange(predicate.copy(op = it)) },
                    )
                    Spacer(Modifier.width(4.dp))
                    SmallTextField(
                        value = formatFieldValueForEdit(predicate.value),
                        hint = "value",
                        onChange = {
                            onChange(predicate.copy(value = parseFieldValue(it, predicate.op)))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                is PredicateDraft.Text -> {
                    SmallTextField(
                        value = predicate.pattern,
                        hint = "pattern",
                        onChange = { onChange(predicate.copy(pattern = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    TargetDropdown(
                        current = predicate.target,
                        onSelect = { onChange(predicate.copy(target = it)) },
                    )
                }

                is PredicateDraft.Unsupported ->
                    Text(text = "Not yet supported", style = RadarType.body, color = RadarColors.ink3)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier =
                    Modifier
                        .clickable(onClickLabel = "Remove condition", onClick = onRemove)
                        .padding(4.dp),
            ) {
                Text(text = "×", style = RadarType.serifH3, color = RadarColors.ink3)
            }
        }
    }
}

@Composable
private fun TypeDropdown(
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier =
            modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(text = current, style = RadarType.small, color = RadarColors.ink, maxLines = 1)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = RadarType.body) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TagDropdown(
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier =
            modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = current.ifBlank { "choose" },
            style = RadarType.small,
            color = if (current.isBlank()) RadarColors.ink3 else RadarColors.ink,
            maxLines = 1,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = RadarType.body) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OpDropdown(
    current: FieldOp,
    onSelect: (FieldOp) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = opSymbol(current)
    Box(
        modifier =
            Modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(text = label, style = RadarType.small, color = RadarColors.ink)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FieldOp.entries.forEach { op ->
                DropdownMenuItem(
                    text = { Text(opSymbol(op), style = RadarType.body) },
                    onClick = {
                        onSelect(op)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TargetDropdown(
    current: TextTarget,
    onSelect: (TextTarget) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(text = current.serialName, style = RadarType.small, color = RadarColors.ink)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TextTarget.entries.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.serialName, style = RadarType.body) },
                    onClick = {
                        onSelect(target)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceDropdown(
    current: String,
    choices: List<SourceChoice>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = choices.find { it.source == current }?.displayName ?: current.ifBlank { "choose" }
    Box(
        modifier =
            modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = currentLabel,
            style = RadarType.small,
            color = if (current.isBlank()) RadarColors.ink3 else RadarColors.ink,
            maxLines = 1,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.displayName, style = RadarType.body) },
                    onClick = {
                        onSelect(choice.source)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun KindDropdown(
    current: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Closed set: only the constants MentionKind defines today.
    val options = listOf(MentionKind.PLACE, MentionKind.PARTY)
    Box(
        modifier =
            Modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = current.ifBlank { "kind" },
            style = RadarType.small,
            color = if (current.isBlank()) RadarColors.ink3 else RadarColors.ink,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = RadarType.body) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FieldNameDropdown(
    current: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Closed set: only FieldNames.SUPPLIED can match anything (ADR 0003 §13).
    val options = FieldNames.SUPPLIED.toList()
    Box(
        modifier =
            Modifier
                .width(90.dp)
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = current.ifBlank { "name" },
            style = RadarType.small,
            color = if (current.isBlank()) RadarColors.ink3 else RadarColors.ink,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = RadarType.body) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SmallTextField(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Box(
        modifier =
            modifier
                .background(RadarColors.paper, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        if (value.isBlank()) {
            Text(text = hint, style = RadarType.small, color = RadarColors.ink3, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = RadarType.small.copy(color = RadarColors.ink),
            cursorBrush = SolidColor(RadarColors.ink),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeliverySection(
    push: Boolean,
    onPushChange: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper2)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Push notification",
                style = RadarType.body,
                color = RadarColors.ink,
            )
            Box(
                modifier =
                    Modifier
                        .clickable { onPushChange(!push) }
                        .padding(4.dp),
            ) {
                Text(
                    text = if (push) "ON" else "OFF",
                    style = RadarType.labelMicro,
                    color = if (push) CategoryColors.Vermilion else RadarColors.ink3,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Sends an alert when the rule matches. OFF means the rule only surfaces.",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
        )
    }
    HardRule()
}

@Composable
private fun SurfacingSection(
    position: Long,
    onPositionChange: (Long) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper2)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Position", style = RadarType.body, color = RadarColors.ink)
            Spacer(Modifier.width(8.dp))
            SmallTextField(
                value = position.toString(),
                hint = "0",
                onChange = {
                    onPositionChange(it.toLongOrNull()?.coerceAtLeast(0) ?: 0L)
                },
                modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Higher values float matches above the stack in views that honour emphasis.",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
        )
    }
    HardRule()
}

@Composable
private fun PreviewSection(
    preview: RulePreview?,
    loading: Boolean,
    draftValid: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper2)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Dry run", style = RadarType.serifH4, color = RadarColors.ink)
            Spacer(Modifier.width(6.dp))
            if (loading) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .background(CategoryColors.Teal),
                )
            } else if (preview is RulePreview.Available) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(CategoryColors.Teal),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(text = "live", style = RadarType.micro, color = CategoryColors.Teal)
                }
            }
            Spacer(Modifier.weight(1f))
            val countText =
                when (preview) {
                    is RulePreview.Available -> preview.matchedCount.toString()
                    else -> "—"
                }
            Text(text = countText, style = RadarType.monoSmall, color = RadarColors.ink)
        }

        Spacer(Modifier.height(4.dp))

        when (preview) {
            is RulePreview.Available -> {
                Text(
                    text =
                        buildString {
                            append("${preview.matchedCount} events would match")
                            append(" · last ${preview.windowDays} days")
                            if (preview.truncated) {
                                append(" · first ${preview.scannedCount} shown")
                            }
                        },
                    style = RadarType.microPlain,
                    color = RadarColors.ink2,
                )

                if (preview.sample.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    preview.sample.forEach { hit ->
                        PreviewHitRow(hit = hit)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            is RulePreview.Unavailable -> {
                Text(
                    text =
                        when (preview.reason) {
                            UnavailableReason.INVALID_DRAFT -> "Finish the condition to preview"
                            UnavailableReason.SERIES_UNSUPPORTED ->
                                "Monitors aren't built yet, so this rule can't be previewed"
                            UnavailableReason.UNKNOWN -> "Couldn't run the preview"
                        },
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                )
            }

            null -> {
                val message =
                    if (draftValid) {
                        "Type to see a live preview"
                    } else {
                        "Finish the condition to preview"
                    }
                Text(
                    text = message,
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                )
            }
        }
    }
    HardRule()
}

private val PREVIEW_TIME_FMT = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

@Composable
private fun PreviewHitRow(hit: com.personalos.app.data.RulePreviewHit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(48.dp)) {
            Text(
                text = PREVIEW_TIME_FMT.format(java.util.Date(hit.timestamp)),
                style = RadarType.monoSmall,
                color = RadarColors.ink2,
            )
            Text(
                text = dayLabel(hit.timestamp).uppercase(),
                style = RadarType.micro,
                color = RadarColors.ink3,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.title,
                style = RadarType.small,
                color = RadarColors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceDisplayName(hit.sourceId),
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    accent: Color,
    note: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        HardRule()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.paper3)
                    .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = RadarType.serifH4,
                color = RadarColors.ink,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (note != null) {
                Text(
                    text = note.uppercase(),
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        HardRule()
    }
}

private fun PredicateDraft.typeLabel(): String =
    when (this) {
        is PredicateDraft.Subject -> "subject"
        is PredicateDraft.Nature -> "nature"
        is PredicateDraft.Marker -> "marker"
        is PredicateDraft.Mention -> "mention"
        is PredicateDraft.Source -> "source"
        is PredicateDraft.Field -> "field"
        is PredicateDraft.Text -> "text"
        is PredicateDraft.Unsupported -> "unsupported"
    }

private fun predicateTypeOptions(): List<String> = listOf("subject", "nature", "marker", "mention", "source", "field", "text")

private fun createDefaultPredicate(
    type: String,
    defaultSource: String = "",
): PredicateDraft =
    when (type) {
        "subject" -> PredicateDraft.Subject(TagGroups.SUBJECTS.firstOrNull() ?: "")
        "nature" -> PredicateDraft.Nature(TagGroups.NATURES.firstOrNull() ?: "")
        "marker" -> PredicateDraft.Marker
        "mention" -> PredicateDraft.Mention(MentionKind.PLACE, "")
        "source" -> PredicateDraft.Source(defaultSource)
        "field" -> PredicateDraft.Field(FieldNames.SUPPLIED.firstOrNull() ?: "", FieldOp.EQ, FieldValue.Str(""))
        "text" -> PredicateDraft.Text("", TextTarget.ANY)
        else -> PredicateDraft.Unsupported
    }

private fun formatFieldValueForEdit(value: FieldValue): String =
    when (value) {
        is FieldValue.Num -> value.value.toString().removeSuffix(".0")
        is FieldValue.Str -> value.value
        is FieldValue.Flag -> value.value.toString()
    }

private fun parseFieldValue(
    text: String,
    op: FieldOp,
): FieldValue {
    if (text.isBlank()) return FieldValue.Str("")
    // Numeric ops force a number; eq/ne/contains stay string unless they parse.
    if (op.isNumeric) {
        return text.toDoubleOrNull()?.let { FieldValue.Num(it) } ?: FieldValue.Str(text)
    }
    return when {
        text.equals("true", ignoreCase = true) -> FieldValue.Flag(true)
        text.equals("false", ignoreCase = true) -> FieldValue.Flag(false)
        text.toDoubleOrNull() != null -> FieldValue.Num(text.toDoubleOrNull()!!)
        else -> FieldValue.Str(text)
    }
}

private fun opSymbol(op: FieldOp): String =
    when (op) {
        FieldOp.EQ -> "="
        FieldOp.NE -> "!="
        FieldOp.LT -> "<"
        FieldOp.LTE -> "<="
        FieldOp.GT -> ">"
        FieldOp.GTE -> ">="
        FieldOp.CONTAINS -> "contains"
    }

/**
 * One selectable source in the rule builder.
 *
 * [source] is the real `events.source` string (`sms`, `rss:thehindu-top`,
 * `userrss:…`, `gnews:…`). [displayName] is what the dropdown shows.
 */
data class SourceChoice(
    val source: String,
    val displayName: String,
)

/**
 * Builds the closed set of source choices for the rule builder.
 *
 * Sources are the only things that can produce events, so a `source` predicate
 * over an id no producer uses silently never matches. The set is therefore
 * closed: every choice maps to a real producer (SMS, a catalog feed, or a user
 * source resolved through [SourceKeys]).
 */
fun sourceChoices(sourceEntities: List<SourceEntity>): List<SourceChoice> {
    val choices = mutableListOf<SourceChoice>()

    // SMS is a hard-coded producer; it is not a row in the source store.
    choices.add(SourceChoice("sms", "SMS"))

    // Catalog feeds write `rss:<id>`; they are not user-editable rows.
    FeedCatalog.SEEDS.forEach { seed ->
        val sourceString = "${FeedCatalog.SOURCE_PREFIX}${seed.id}"
        choices.add(SourceChoice(sourceString, seed.name))
    }

    // User sources resolve through SourceKeys to their real event source string.
    sourceEntities.forEach { entity ->
        val spec = runCatching { SourceSpecs.parse(entity.kind, entity.specJson) }.getOrNull() ?: return@forEach
        val sourceString = SourceKeys.sourceFor(entity.id, spec)
        choices.add(SourceChoice(sourceString, entity.name))
    }

    return choices
}
