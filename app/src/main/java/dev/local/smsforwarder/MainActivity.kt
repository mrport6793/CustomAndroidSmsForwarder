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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
 * Single-screen settings UI: pick Email / SMS / Both, fill in the destination(s),
 * set optional filters, save. The forwarding logic does not depend on the UI;
 * the manifest receiver works as soon as config is saved and RECEIVE_SMS is
 * granted.
 *
 * Note: this app does NOT request READ_SMS — only RECEIVE_SMS (to wake on the
 * incoming-SMS broadcast) and SEND_SMS (for SMS forwarding).
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
                SettingsScreen(
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

    /** Opens the system dialog to exempt this app from battery optimization. */
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: Prefs,
    isIgnoringBatteryOptimizations: () -> Boolean,
    onRequestBatteryExemption: () -> Unit,
    isSmsPermissionGranted: () -> Boolean,
) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(prefs.mode) }

    // Email (Resend)
    var apiKey by remember { mutableStateOf(prefs.resendApiKey) }
    var from by remember { mutableStateOf(prefs.resendFrom) }
    var to by remember { mutableStateOf(prefs.emailTo) }
    var showKey by remember { mutableStateOf(false) }

    // SMS
    var dest by remember { mutableStateOf(prefs.destNumber) }

    // Filters
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
        val how = when (mode) {
            Prefs.Mode.EMAIL -> "email"
            Prefs.Mode.SMS -> "SMS"
            Prefs.Mode.BOTH -> "email + SMS"
        }
        Toast.makeText(context, "Saved — forwarding via $how is active", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SMS Forwarder") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
            // --- Status pills ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(ok = isSmsPermissionGranted(), label = "SMS permission")
                val battOk = isIgnoringBatteryOptimizations()
                StatusPill(
                    ok = battOk,
                    label = if (battOk) "Battery OK" else "Fix battery",
                    onClick = if (battOk) null else onRequestBatteryExemption,
                )
            }

            Text(
                "Pick a mode, add a destination, then Save. Forwarding runs in the background — even after a reboot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Forwarding card ---
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

            // --- Filters card ---
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
        }
    }
}

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
