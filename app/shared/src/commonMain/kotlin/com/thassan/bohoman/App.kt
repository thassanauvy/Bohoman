package com.thassan.bohoman

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.thassan.bohoman.contracts.*
import com.thassan.bohoman.core.network.*
import com.thassan.bohoman.core.time.*
import com.thassan.bohoman.feature.dashboard.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.Instant

private val BohomanColors = lightColorScheme(
    primary = Color(0xFF225C4B), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDE3), onPrimaryContainer = Color(0xFF153E32),
    secondary = Color(0xFF806141), secondaryContainer = Color(0xFFF1E5D3),
    background = Color(0xFFF8F7F2), surface = Color(0xFFFFFEFA),
    surfaceVariant = Color(0xFFEAECE4), onSurfaceVariant = Color(0xFF56615A),
)

@Composable
fun App(
    config: AppConfig = AppConfig(),
    googleSignIn: (suspend () -> String)? = null,
    tokenStore: RefreshTokenStore? = null,
) {
    val scope = rememberCoroutineScope()
    val effectiveTokenStore = tokenStore ?: remember { MemoryRefreshTokenStore() }
    val api = remember(config, effectiveTokenStore) { BohomanApi(config, effectiveTokenStore) }
    val model = remember(api) { DashboardViewModel(api, scope) }
    val state by model.state.collectAsState()
    val signIn by rememberUpdatedState(googleSignIn)
    var page by remember { mutableStateOf("Home") }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    val zone = remember { TimeZone.currentSystemDefault() }
    LaunchedEffect(model) { model.restore() }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Clock.System.now() } }
    LaunchedEffect(state.user?.id) { editor = null; page = "Home" }
    DisposableEffect(api) { onDispose { api.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }

    MaterialTheme(colorScheme = BohomanColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeContentPadding().fillMaxSize().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bohoman", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("A little clarity for what’s ahead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.user != null) TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { ErrorBanner(it, model::dismissError) }
                when {
                    state.initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.user == null -> LoginScreen(config, signIn != null, state.busy,
                        canRetryRestore = state.restoreFailed, onRetryRestore = model::restore,
                        onSignIn = { signIn?.let(model::signIn) })
                    else -> {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Home", "Tasks", "Countdowns", "Settings").forEach { title ->
                                FilterChip(selected = page == title, onClick = { page = title }, label = { Text(title) })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            when (page) {
                                "Home" -> DashboardScreen(state, now, zone, onNewTask = { editor = Editor.Task(null) }, onNewCountdown = { editor = Editor.Countdown(null) }, onOpen = { item ->
                                    editor = when (item.source) {
                                        DashboardSource.TASK -> state.tasks.firstOrNull { it.id == item.id }?.let { Editor.Task(it) }
                                        DashboardSource.COUNTDOWN -> state.countdowns.firstOrNull { it.id == item.id }?.let { Editor.Countdown(it) }
                                    }
                                })
                                "Tasks" -> TaskListScreen(state, now, zone, { editor = Editor.Task(null) }, { editor = Editor.Task(it) }, model::toggleTask)
                                "Countdowns" -> CountdownListScreen(state, now, zone, { editor = Editor.Countdown(null) }, { editor = Editor.Countdown(it) })
                                "Settings" -> SettingsScreen(requireNotNull(state.user), state.busy, model::logout)
                            }
                        }
                    }
                }
            }
        }
        editor?.let { active ->
            ItemEditor(active, state.busy, state.error, onDismiss = { if (!state.busy) editor = null },
                onSaveTask = { original, value, completed -> model.saveTask(original, value, completed) { editor = null } },
                onSaveCountdown = { original, value -> model.saveCountdown(original, value) { editor = null } },
                onDeleteTask = { model.deleteTask(it) { editor = null } },
                onDeleteCountdown = { model.deleteCountdown(it) { editor = null } },
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun LoginScreen(config: AppConfig, configured: Boolean, busy: Boolean, canRetryRestore: Boolean, onRetryRestore: () -> Unit, onSignIn: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Your plans.\nOne calm place.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text("Keep your tasks and personal countdowns together, wherever your day takes you.", style = MaterialTheme.typography.bodyLarge)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A clear view of what matters", style = MaterialTheme.typography.titleMedium)
                    Text("Tasks with deadlines appear on your home screen automatically. Your dates stay your dates, across devices and time zones.")
                }
            }
            Button(onClick = onSignIn, enabled = configured && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continue with Google") }
            if (canRetryRestore) OutlinedButton(onClick = onRetryRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Retry saved session")
            }
            if (!configured) Text(config.signInSetupMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your tasks and countdowns are private to your account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardScreen(state: DashboardViewModel.State, now: Instant, zone: TimeZone, onNewTask: () -> Unit, onNewCountdown: () -> Unit, onOpen: (DashboardItem) -> Unit) {
    LazyColumn(Modifier.widthIn(max = 960.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Make room for what matters.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("${state.tasks.count { !it.completed }} open tasks  ·  ${state.countdowns.size} personal countdowns", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNewTask, enabled = !state.busy) { Text("Add task") }
                    OutlinedButton(onClick = onNewCountdown, enabled = !state.busy) { Text("Add countdown") }
                }
                Text("On the horizon", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (state.dashboard.isEmpty()) item { EmptyState("A fresh start", "Add a countdown or give a task a deadline to see it here.") }
        items(state.dashboard, key = { "${it.source}:${it.id}" }) { item ->
            Card(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (item.source == DashboardSource.TASK) "TASK" else "PERSONAL COUNTDOWN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    Text(countdownLabel(item.target, now, zone), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(deadlineLabel(item.target), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
                }
            }
        }
    }
}

@Composable
private fun TaskListScreen(state: DashboardViewModel.State, now: Instant, zone: TimeZone, onNew: () -> Unit, onEdit: (TaskDto) -> Unit, onToggle: (TaskDto) -> Unit) {
    var showCompleted by remember { mutableStateOf(false) }
    val visible = state.tasks.filter { it.completed == showCompleted }
    LazyColumn(Modifier.widthIn(max = 960.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your tasks", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onNew, enabled = !state.busy) { Text("Add task") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showCompleted, { showCompleted = false }, label = { Text("Open (${state.tasks.count { !it.completed }})") })
                FilterChip(showCompleted, { showCompleted = true }, label = { Text("Completed (${state.tasks.count { it.completed }})") })
            }
        }
        if (visible.isEmpty()) item { EmptyState(if (showCompleted) "Your progress will live here" else "Nothing on your list yet", if (showCompleted) "Complete a task to keep a record of what you’ve done." else "Add your first task. A deadline is optional.") }
        items(visible, key = { it.id }) { task ->
            Card(onClick = { onEdit(task) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = task.completed, onCheckedChange = { onToggle(task) }, enabled = !state.busy)
                    Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                        Text(listOf("No priority", "Low priority", "Medium priority", "High priority")[task.priority.coerceIn(0, 3)], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        task.deadline?.let {
                            Text("${countdownLabel(it, now, zone)} · ${deadlineLabel(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        task.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                    }
                    TextButton(onClick = { onEdit(task) }, enabled = !state.busy) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun CountdownListScreen(state: DashboardViewModel.State, now: Instant, zone: TimeZone, onNew: () -> Unit, onEdit: (CountdownDto) -> Unit) {
    LazyColumn(Modifier.widthIn(max = 960.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Countdowns", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onNew, enabled = !state.busy) { Text("Add countdown") }
            }
        }
        item { Text("Dates to look forward to, big and small.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.countdowns.isEmpty()) item { EmptyState("What are you looking forward to?", "Add a birthday, a trip, or your next milestone.") }
        items(state.countdowns, key = { it.id }) { countdown ->
            Card(onClick = { onEdit(countdown) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(countdown.title, style = MaterialTheme.typography.titleLarge)
                    Text(countdownLabel(countdown.target, now, zone), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(deadlineLabel(countdown.target), style = MaterialTheme.typography.bodySmall)
                    countdown.description?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 3) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(user: UserDto, busy: Boolean, onLogout: () -> Unit) {
    Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Your account", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(user.displayName ?: "Bohoman member", style = MaterialTheme.typography.titleLarge)
                user.email?.let { Text(it) }
                HorizontalDivider()
                Text("Your device’s time zone: ${TimeZone.currentSystemDefault().id}")
                Text("Calendar dates stay as you entered them. Exact-time countdowns refer to the same moment on every device.", style = MaterialTheme.typography.bodyMedium)
                Text("Your lists refresh when you return to the app. You can also refresh them anytime.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onLogout, enabled = !busy) { Text("Sign out of this device") }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private sealed interface Editor {
    data class Task(val original: TaskDto?) : Editor
    data class Countdown(val original: CountdownDto?) : Editor
}

@Composable
private fun ItemEditor(
    editor: Editor,
    busy: Boolean,
    serverError: String?,
    onDismiss: () -> Unit,
    onSaveTask: (TaskDto?, CreateTaskRequest, Boolean) -> Unit,
    onSaveCountdown: (CountdownDto?, CreateCountdownRequest) -> Unit,
    onDeleteTask: (TaskDto) -> Unit,
    onDeleteCountdown: (CountdownDto) -> Unit,
) {
    val task = (editor as? Editor.Task)?.original
    val countdown = (editor as? Editor.Countdown)?.original
    val isTask = editor is Editor.Task
    val isNew = task == null && countdown == null
    val initialDeadline = task?.deadline ?: countdown?.target
    var title by remember(editor) { mutableStateOf(task?.title ?: countdown?.title.orEmpty()) }
    var description by remember(editor) { mutableStateOf(task?.description ?: countdown?.description.orEmpty()) }
    var priority by remember(editor) { mutableIntStateOf(task?.priority ?: 0) }
    var completed by remember(editor) { mutableStateOf(task?.completed ?: false) }
    var mode by remember(editor) { mutableIntStateOf(when { initialDeadline?.instant != null -> 2; initialDeadline?.date != null || !isTask -> 1; else -> 0 }) }
    var date by remember(editor) { mutableStateOf(initialDeadline?.date.orEmpty()) }
    var zone by remember(editor) { mutableStateOf(initialDeadline?.timeZone ?: TimeZone.currentSystemDefault().id) }
    var localDateTime by remember(editor) { mutableStateOf(initialDeadline?.instant?.let { Instant.parse(it).toLocalDateTime(TimeZone.of(zone)).toString() }.orEmpty()) }
    var validationError by remember(editor) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(editor) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 760.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${if (isNew) "New" else "Edit"} ${if (isTask) "task" else "countdown"}", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(title, { title = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(description, { description = it.take(10_000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes (optional)") }, minLines = 2, maxLines = 4, enabled = !busy)
                if (isTask) {
                    Text("Priority", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("None", "Low", "Medium", "High").forEachIndexed { value, label -> FilterChip(priority == value, { priority = value }, label = { Text(label) }, enabled = !busy) }
                    }
                    if (!isNew) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(completed, { completed = it }, enabled = !busy)
                        Text("Completed")
                    }
                }
                Text(if (isTask) "Deadline" else "Target", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isTask) FilterChip(mode == 0, { mode = 0 }, label = { Text("No deadline") }, enabled = !busy)
                    FilterChip(mode == 1, { mode = 1 }, label = { Text("Calendar date") }, enabled = !busy)
                    FilterChip(mode == 2, { mode = 2 }, label = { Text("Exact time") }, enabled = !busy)
                }
                when (mode) {
                    1 -> {
                        OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, placeholder = { Text("2026-12-31") }, singleLine = true, enabled = !busy)
                        Text("An all-day date stays the same in every time zone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> {
                        OutlinedTextField(localDateTime, { localDateTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Local date and time") }, placeholder = { Text("2026-12-31T18:30") }, supportingText = { Text("YYYY-MM-DDTHH:mm") }, singleLine = true, enabled = !busy)
                        OutlinedTextField(zone, { zone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Time zone") }, placeholder = { Text("Asia/Dhaka") }, singleLine = true, enabled = !busy)
                    }
                }
                (validationError ?: serverError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Button(enabled = !busy, onClick = {
                        try {
                            require(title.trim().isNotBlank()) { "Add a title." }
                            val deadline = when (mode) {
                                1 -> parseDeadline(date, "", "", required = true)
                                2 -> parseLocalDeadline(localDateTime, zone, initialDeadline)
                                else -> null
                            }
                            validationError = null
                            val notes = description.trim().ifBlank { null }
                            if (isTask) onSaveTask(task, CreateTaskRequest(title.trim(), notes, priority, deadline), completed)
                            else onSaveCountdown(countdown, CreateCountdownRequest(title.trim(), notes, requireNotNull(deadline)))
                        } catch (failure: IllegalArgumentException) {
                            validationError = failure.message ?: "Check the date and time fields."
                        }
                    }) { Text(if (busy) "Saving…" else "Save") }
                }
                if (!isNew) {
                    HorizontalDivider()
                    if (confirmDelete) {
                        Text("Delete this ${if (isTask) "task" else "countdown"}? It will disappear from all your devices.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text("Keep it") }
                            Button(onClick = { task?.let(onDeleteTask); countdown?.let(onDeleteCountdown) }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                        }
                    } else TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete ${if (isTask) "task" else "countdown"}", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
