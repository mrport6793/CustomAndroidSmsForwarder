package dev.local.smsforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Two screens, no nav dependency — a simple screen-state switch:
 *   Home     — at-a-glance status: mode, destinations, filters, readiness.
 *   Settings — the config form (Email / SMS / Both, destinations, filters).
 *
 * The forwarding logic doesn't depend on the UI; the manifest receiver works as
 * soon as config is saved and RECEIVE_SMS is granted. This app does NOT request
 * READ_SMS — only RECEIVE_SMS and SEND_SMS.
 */
class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* result ignored here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()

        setContent {
            SmsForwarderTheme {
                ForwarderApp(
                    prefs = Prefs(this),
                    isIgnoringBatteryOptimizations = { isIgnoringBatteryOptimizations() },
                    onRequestBatteryExemption = { requestBatteryExemption() },
                    isSmsPermissionGranted = { isReceiveSmsGranted() },
                )
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun isReceiveSmsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun ForwarderApp(
    prefs: Prefs,
    isIgnoringBatteryOptimizations: () -> Boolean,
    onRequestBatteryExemption: () -> Unit,
    isSmsPermissionGranted: () -> Boolean,
) {
    // First run (nothing configured yet) opens straight into Settings.
    var screen by remember {
        mutableStateOf(if (forwardingReady(prefs, isSmsPermissionGranted())) Screen.HOME else Screen.SETTINGS)
    }
    // Bump on save so Home re-reads prefs after returning from Settings.
    var revision by remember { mutableStateOf(0) }

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(
            prefs = prefs,
            revision = revision,
            isSmsPermissionGranted = isSmsPermissionGranted,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            onRequestBatteryExemption = onRequestBatteryExemption,
            onEdit = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            prefs = prefs,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            onRequestBatteryExemption = onRequestBatteryExemption,
            isSmsPermissionGranted = isSmsPermissionGranted,
            onBack = { screen = Screen.HOME },
            onSaved = { revision++; screen = Screen.HOME },
        )
    }
}

/* ----------------------------- Home ----------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    prefs: Prefs,
    revision: Int,
    isSmsPermissionGranted: () -> Boolean,
    isIgnoringBatteryOptimizations: () -> Boolean,
    onRequestBatteryExemption: () -> Unit,
    onEdit: () -> Unit,
) {
    // `revision` is read so a save invalidates this composition and re-reads prefs.
    @Suppress("UNUSED_EXPRESSION") revision
    val context = LocalContext.current
    val mode = prefs.mode
    val smsOk = isSmsPermissionGranted()
    val ready = forwardingReady(prefs, smsOk)

    // Bumped by Refresh/Reset so the counters re-read from disk.
    var statsRefresh by remember { mutableStateOf(0) }
    val stats = remember(statsRefresh) { Stats(context) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SMS Forwarder") },
                actions = { TextButton(onClick = onEdit) { Text("Settings") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroCard(ready = ready, mode = mode)

            SectionCard(title = "Forwarding to") {
                if (mode == Prefs.Mode.EMAIL || mode == Prefs.Mode.BOTH) {
                    InfoRow("Email", prefs.emailTo.ifBlank { "— not set —" })
                    InfoRow("From", prefs.resendFrom.ifBlank { Prefs.DEFAULT_FROM })
                }
                if (mode == Prefs.Mode.BOTH) HorizontalDivider()
                if (mode == Prefs.Mode.SMS || mode == Prefs.Mode.BOTH) {
                    InfoRow("SMS", prefs.destNumber.ifBlank { "— not set —" })
                }
            }

            SectionCard(title = "Filters") {
                Text(filterSummary(prefs), style = MaterialTheme.typography.bodyMedium)
            }

            SectionCard(title = "Activity") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(Modifier.weight(1f), stats.forwarded, "Forwarded")
                    StatTile(Modifier.weight(1f), stats.filtered, "Filtered")
                    StatTile(Modifier.weight(1f), stats.failed, "Failed")
                }
                Text(
                    "Last forwarded: ${relativeTime(stats.lastForwardedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { statsRefresh++ }) { Text("Refresh") }
                    TextButton(onClick = { stats.reset(); statsRefresh++ }) { Text("Reset") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(ok = smsOk, label = "SMS permission")
                val battOk = isIgnoringBatteryOptimizations()
                StatusPill(
                    ok = battOk,
                    label = if (battOk) "Battery OK" else "Fix battery",
                    onClick = if (battOk) null else onRequestBatteryExemption,
                )
            }

            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text(if (ready) "Edit settings" else "Finish setup")
            }
        }
    }
}

@Composable
private fun HeroCard(ready: Boolean, mode: Prefs.Mode) {
    val container = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = onContainer.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (ready) "✓" else "!",
                        style = MaterialTheme.typography.headlineSmall,
                        color = onContainer,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (ready) "Forwarding is active" else "Setup not finished",
                    style = MaterialTheme.typography.titleLarge,
                    color = onContainer,
                )
                Text(
                    if (ready) "Every incoming SMS is forwarded via ${modeLabel(mode)}."
                    else "Add a destination and grant SMS permission to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                )
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, count: Long, label: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(width = 60.dp, height = 24.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/* --------------------------- Settings --------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: Prefs,
    isIgnoringBatteryOptimizations: () -> Boolean,
    onRequestBatteryExemption: () -> Unit,
    isSmsPermissionGranted: () -> Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(prefs.mode) }

    var apiKey by remember { mutableStateOf(prefs.resendApiKey) }
    var from by remember { mutableStateOf(prefs.resendFrom) }
    var to by remember { mutableStateOf(prefs.emailTo) }
    var showKey by remember { mutableStateOf(false) }

    var dest by remember { mutableStateOf(prefs.destNumber) }

    var senders by remember { mutableStateOf(prefs.senderAllowList) }
    var keyword by remember { mutableStateOf(prefs.keyword) }
    var regex by remember { mutableStateOf(prefs.regex) }

    val showEmail = mode == Prefs.Mode.EMAIL || mode == Prefs.Mode.BOTH
    val showSms = mode == Prefs.Mode.SMS || mode == Prefs.Mode.BOTH

    val onSave: () -> Unit = save@{
        val missing = buildList {
            if (showEmail && apiKey.isBlank()) add("Resend API key")
            if (showEmail && to.isBlank()) add("destination email")
            if (showSms && dest.isBlank()) add("destination number")
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(context, "Add: ${missing.joinToString(", ")}", Toast.LENGTH_LONG).show()
            return@save
        }
        prefs.mode = mode
        if (showEmail) {
            prefs.resendApiKey = apiKey.trim()
            prefs.resendFrom = from.trim().ifBlank { Prefs.DEFAULT_FROM }
            prefs.emailTo = to.trim()
        }
        if (showSms) prefs.destNumber = dest.trim()
        prefs.senderAllowList = senders.trim()
        prefs.keyword = keyword.trim()
        prefs.regex = regex.trim()
        Toast.makeText(context, "Saved — forwarding via ${modeLabel(mode)} is active", Toast.LENGTH_SHORT).show()
        onSaved()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Forwarding") {
                Text("Forward each SMS via", style = MaterialTheme.typography.labelLarge)
                ModeSelector(mode = mode, onSelect = { mode = it })

                if (showEmail) {
                    SectionLabel("Email (Resend)")
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("Resend API key (re_…)") }, singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = from, onValueChange = { from = it },
                        label = { Text("From address") }, singleLine = true,
                        supportingText = {
                            Text("Default onboarding@resend.dev only delivers to your Resend account email. Use a verified-domain address to send anywhere.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = to, onValueChange = { to = it },
                        label = { Text("Forward to (email)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (showSms) {
                    SectionLabel("SMS to a number")
                    OutlinedTextField(
                        value = dest, onValueChange = { dest = it },
                        label = { Text("Forward to (phone number)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        supportingText = { Text("Carrier SMS charges may apply per forwarded text.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionCard(title = "Filters (optional)") {
                Text(
                    "Leave blank to forward everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = senders, onValueChange = { senders = it },
                    label = { Text("Allowed senders") }, singleLine = true,
                    supportingText = { Text("Comma-separated; matches if the sender contains an entry.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyword, onValueChange = { keyword = it },
                    label = { Text("Required keyword") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = regex, onValueChange = { regex = it },
                    label = { Text("Required regex") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

/* --------------------------- Shared --------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: Prefs.Mode, onSelect: (Prefs.Mode) -> Unit) {
    val options = listOf(Prefs.Mode.EMAIL to "Email", Prefs.Mode.SMS to "SMS", Prefs.Mode.BOTH to "Both")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun StatusPill(ok: Boolean, label: String, onClick: (() -> Unit)? = null) {
    val bg = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (ok) "✓" else "!", color = fg, style = MaterialTheme.typography.labelLarge)
            Text(label, color = fg, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "never"
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

private fun modeLabel(mode: Prefs.Mode): String = when (mode) {
    Prefs.Mode.EMAIL -> "email"
    Prefs.Mode.SMS -> "SMS"
    Prefs.Mode.BOTH -> "email + SMS"
}

private fun forwardingReady(prefs: Prefs, smsGranted: Boolean): Boolean {
    if (!smsGranted) return false
    val emailOk = prefs.resendApiKey.isNotBlank() && prefs.emailTo.isNotBlank()
    val smsOk = prefs.destNumber.isNotBlank()
    return when (prefs.mode) {
        Prefs.Mode.EMAIL -> emailOk
        Prefs.Mode.SMS -> smsOk
        Prefs.Mode.BOTH -> emailOk && smsOk
    }
}

private fun filterSummary(prefs: Prefs): String {
    val parts = buildList {
        if (prefs.senderAllowList.isNotBlank()) add("only allowed senders")
        if (prefs.keyword.isNotBlank()) add("body contains \"${prefs.keyword}\"")
        if (prefs.regex.isNotBlank()) add("body matches a regex")
    }
    return if (parts.isEmpty()) "Forwarding all incoming messages." else "Only: ${parts.joinToString("; ")}."
}
