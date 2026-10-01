/*
 * AnLite Backup: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.anlite.backup.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anlite.backup.DialogMode
import com.anlite.backup.EnabledFilter
import com.anlite.backup.LatestFilter
import com.anlite.backup.LaunchableFilter
import com.anlite.backup.MAIN_FILTER_DEFAULT
import com.anlite.backup.MAIN_FILTER_DEFAULT_WITHOUT_SPECIAL
import com.anlite.backup.MODE_UNSET
import com.anlite.backup.R
import com.anlite.backup.UpdatedFilter
import com.anlite.backup.data.dbs.entity.Schedule
import com.anlite.backup.data.entity.ColoringState
import com.anlite.backup.data.preferences.traceDebug
import com.anlite.backup.enabledFilterChipItems
import com.anlite.backup.latestFilterChipItems
import com.anlite.backup.launchableFilterChipItems
import com.anlite.backup.mainFilterChipItems
import com.anlite.backup.mainFilterChipItemsSansSpecial
import com.anlite.backup.manager.tasks.ScheduleWork
import com.anlite.backup.scheduleBackupModeChipItems
import com.anlite.backup.ui.compose.component.ActionButton
import com.anlite.backup.ui.compose.component.CardButton
import com.anlite.backup.ui.compose.component.CheckChip
import com.anlite.backup.ui.compose.component.ExpandableBlock
import com.anlite.backup.ui.compose.component.MultiSelectableChipGroup
import com.anlite.backup.ui.compose.component.OutlinedActionButton
import com.anlite.backup.ui.compose.component.RoundButton
import com.anlite.backup.ui.compose.component.SelectableChipGroup
import com.anlite.backup.ui.compose.component.TextEditBlock
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.CheckCircle
import com.anlite.backup.ui.compose.icons.phosphor.Clock
import com.anlite.backup.ui.compose.icons.phosphor.ClockClockwise
import com.anlite.backup.ui.compose.icons.phosphor.Play
import com.anlite.backup.ui.compose.icons.phosphor.Prohibit
import com.anlite.backup.ui.compose.icons.phosphor.TrashSimple
import com.anlite.backup.ui.compose.icons.phosphor.X
import com.anlite.backup.ui.dialogs.ActionsDialogUI
import com.anlite.backup.ui.dialogs.BaseDialog
import com.anlite.backup.ui.dialogs.BlockListDialogUI
import com.anlite.backup.ui.dialogs.CustomListDialogUI
import com.anlite.backup.ui.dialogs.IntPickerDialogUI
import com.anlite.backup.ui.dialogs.TimePickerDialogUI
import com.anlite.backup.updatedFilterChipItems
import com.anlite.backup.utils.extensions.koinAnLiteViewModel
import com.anlite.backup.utils.getStartScheduleMessage
import com.anlite.backup.utils.specialBackupsEnabled
import com.anlite.backup.utils.timeLeft
import com.anlite.backup.viewmodels.ScheduleVM
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePage(
    scheduleId: Long,
    viewModel: ScheduleVM = koinAnLiteViewModel(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val openDialog = remember { mutableStateOf(false) }
    val dialogProps: MutableState<Pair<DialogMode, Schedule>> = remember {
        mutableStateOf(Pair(DialogMode.NONE, Schedule()))
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(scheduleId) {
        viewModel.setSchedule(scheduleId)
    }

    state.schedule?.let { schedule ->
        val times by schedule.timeLeft().collectAsStateWithLifecycle()

        fun refresh(
            schedule: Schedule,
            rescheduleBoolean: Boolean,
        ) = viewModel.updateSchedule(schedule, rescheduleBoolean)

        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                    headlineContent = {
                        TextEditBlock(
                            initText = schedule.name,
                            label = stringResource(R.string.sched_name),
                            onCancel = { },
                            onSave = {
                                refresh(
                                    schedule.copy(name = it),
                                    rescheduleBoolean = false,
                                )
                            }
                        )
                    },
                    trailingContent = {
                        RoundButton(
                            icon = Phosphor.X,
                            description = stringResource(id = R.string.dismiss),
                            onClick = { onDismiss() }
                        )
                    }
                )
                HorizontalDivider(
                    thickness = 2.dp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f, true)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CardButton(
                            modifier = Modifier.weight(0.5f),
                            icon = Phosphor.Clock,
                            coloring = ColoringState.Neutral,
                            description = "${stringResource(id = R.string.sched_hourOfDay)} ${
                                LocalTime.of(
                                    schedule.timeHour,
                                    schedule.timeMinute
                                )
                            }",
                        ) {
                            dialogProps.value = Pair(DialogMode.TIME_PICKER, schedule)
                            openDialog.value = true
                        }
                        CardButton(
                            modifier = Modifier.weight(0.5f),
                            icon = Phosphor.ClockClockwise,
                            coloring = ColoringState.Neutral,
                            description = "${stringResource(id = R.string.sched_interval)} ${schedule.interval}",
                        ) {
                            dialogProps.value = Pair(DialogMode.INTERVAL_SETTER, schedule)
                            openDialog.value = true
                        }
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CardButton(
                            modifier = Modifier.weight(0.5f),
                            icon = Phosphor.CheckCircle,
                            description = stringResource(id = R.string.customListTitle),
                            coloring = if (state.customList.isNotEmpty()) ColoringState.Positive
                            else ColoringState.Negative,
                        ) {
                            dialogProps.value = Pair(DialogMode.CUSTOMLIST, schedule)
                            openDialog.value = true
                        }
                        CardButton(
                            modifier = Modifier.weight(0.5f),
                            icon = Phosphor.Prohibit,
                            description = stringResource(id = R.string.sched_blocklist),
                            coloring = if (state.blockList.isNotEmpty()) ColoringState.Positive
                            else ColoringState.Negative,
                        ) {
                            dialogProps.value = Pair(DialogMode.BLOCKLIST, schedule)
                            openDialog.value = true
                        }
                    }
                }
                item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_backup),
                        preExpanded = true,
                    ) {
                        MultiSelectableChipGroup(
                            list = scheduleBackupModeChipItems,
                            selectedFlags = schedule.mode
                        ) { flags, flag ->
                            traceDebug { "*** onClick mode ${schedule.mode} xor $flag -> $flags (${schedule.mode xor flag})" }
                            refresh(
                                schedule.copy(mode = flags),
                                false,
                            )
                        }
                    }
                }
                item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_app),
                        preExpanded = schedule.filter != if (specialBackupsEnabled) MAIN_FILTER_DEFAULT
                        else MAIN_FILTER_DEFAULT_WITHOUT_SPECIAL,
                    ) {
                        MultiSelectableChipGroup(
                            list = if (specialBackupsEnabled) mainFilterChipItems
                            else mainFilterChipItemsSansSpecial,
                            selectedFlags = schedule.filter
                        ) { flags, flag ->
                            refresh(
                                schedule.copy(filter = flags),
                                false,
                            )
                        }
                    }
                }
                item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_launchable),
                        preExpanded = schedule.launchableFilter != LaunchableFilter.ALL.ordinal,
                    ) {
                        SelectableChipGroup(
                            list = launchableFilterChipItems,
                            selectedFlag = schedule.launchableFilter
                        ) { flag ->
                            refresh(
                                schedule.copy(launchableFilter = flag),
                                false,
                            )
                        }
                    }
                }
                item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_updated),
                        preExpanded = schedule.updatedFilter != UpdatedFilter.ALL.ordinal,
                    ) {
                        SelectableChipGroup(
                            list = updatedFilterChipItems,
                            selectedFlag = schedule.updatedFilter
                        ) { flag ->
                            refresh(
                                schedule.copy(updatedFilter = flag),
                                false,
                            )
                        }
                    }
                }
                item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_latest),
                        preExpanded = schedule.latestFilter != LatestFilter.ALL.ordinal,
                    ) {
                        SelectableChipGroup(
                            list = latestFilterChipItems,
                            selectedFlag = schedule.latestFilter
                        ) { flag ->
                            refresh(
                                schedule.copy(latestFilter = flag),
                                false,
                            )
                        }
                    }
                }
                item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_enabled),
                        preExpanded = schedule.enabledFilter != EnabledFilter.ALL.ordinal,
                    ) {
                        SelectableChipGroup(
                            list = enabledFilterChipItems,
                            selectedFlag = schedule.enabledFilter
                        ) { flag ->
                            refresh(
                                schedule.copy(enabledFilter = flag),
                                false,
                            )
                        }
                    }
                }
                if (state.tagsList.isNotEmpty()) item {
                    ExpandableBlock(
                        heading = stringResource(id = R.string.filters_tags),
                        preExpanded = schedule.tagsList.isNotEmpty(),
                    ) {
                        MultiSelectableChipGroup(
                            list = state.tagsList,
                            selected = schedule.tagsList,
                        ) { tags ->
                            refresh(
                                schedule.copy(tagsList = tags),
                                false,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                thickness = 2.dp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CheckChip(
                        checked = schedule.enabled,
                        textId = R.string.sched_checkbox,
                        checkedTextId = R.string.enabled,
                        onCheckedChange = { checked ->
                            refresh(
                                schedule.copy(enabled = checked),
                                true,
                            )
                        }
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        maxLines = 2
                    ) {
                        if (schedule.enabled) Text(text = "⏳ ${times.second}")
                        Text(text = "🕒 ${times.first}")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedActionButton(
                        text = stringResource(id = R.string.delete),
                        icon = Phosphor.TrashSimple,
                        coloring = ColoringState.Negative,
                    ) {
                        dialogProps.value = Pair(DialogMode.DELETE, schedule)
                        openDialog.value = true
                    }
                    ActionButton(
                        text = stringResource(id = R.string.sched_activateButton),
                        icon = Phosphor.Play,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            dialogProps.value = Pair(DialogMode.SCHEDULE_RUN, schedule)
                            openDialog.value = true
                        }
                    )
                }
            }
        }

        if (openDialog.value) BaseDialog(onDismiss = { openDialog.value = false }) {
            dialogProps.value.let { (dialogMode, schedule) ->
                when (dialogMode) {
                    DialogMode.BLOCKLIST
                        -> BlockListDialogUI(
                        schedule = schedule,
                        openDialogCustom = openDialog,
                    ) { newSet ->
                        refresh(
                            schedule.copy(blockList = newSet),
                            rescheduleBoolean = false,
                        )
                    }

                    DialogMode.CUSTOMLIST
                        -> CustomListDialogUI(
                        schedule = schedule,
                        openDialogCustom = openDialog,
                    ) { newSet ->
                        refresh(
                            schedule.copy(customList = newSet),
                            rescheduleBoolean = false,
                        )
                    }

                    DialogMode.DELETE -> {
                        ActionsDialogUI(
                            titleText = schedule.name,
                            messageText = stringResource(id = R.string.deleteScheduleDialogMessage),
                            onDismiss = { openDialog.value = false },
                            primaryText = stringResource(id = R.string.dialogYes),
                            primaryAction = {
                                viewModel.deleteSchedule()
                                onDismiss()
                            }
                        )
                    }

                    DialogMode.TIME_PICKER
                        -> TimePickerDialogUI(
                        state = rememberTimePickerState(
                            initialHour = schedule.timeHour,
                            initialMinute = schedule.timeMinute,
                        ),
                        openDialogCustom = openDialog,
                    ) { hour, minute ->
                        refresh(
                            schedule.copy(timeHour = hour, timeMinute = minute),
                            rescheduleBoolean = true,
                        )
                    }

                    DialogMode.INTERVAL_SETTER
                        -> IntPickerDialogUI(
                        value = schedule.interval,
                        defaultValue = 1,
                        entries = (1..30).toList(),
                        openDialogCustom = openDialog,
                    ) {
                        refresh(
                            schedule.copy(interval = it),
                            rescheduleBoolean = true,
                        )
                    }

                    DialogMode.SCHEDULE_RUN
                        -> ActionsDialogUI(
                        titleText = "${schedule.name}: ${stringResource(R.string.sched_activateButton)}?",
                        messageText = context.getStartScheduleMessage(
                            schedule = schedule,
                            globalBlockList = state.globalBlockList,
                            tagsMap = state.tagsMap,
                            allTags = state.tagsList,
                        ),
                        onDismiss = { openDialog.value = false },
                        primaryText = stringResource(R.string.dialogOK),
                        primaryAction = {
                            if (schedule.mode != MODE_UNSET)
                                ScheduleWork.enqueueImmediate(schedule)
                        },
                    )

                    else -> {}
                }
            }
        }
    }
}
