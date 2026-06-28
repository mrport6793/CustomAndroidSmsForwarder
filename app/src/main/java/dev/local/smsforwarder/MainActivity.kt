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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Single-screen settings UI: pick EMAIL or SMS mode, fill in the destination,
 * set optional filters, save. The forwarding logic does not depend on the UI;
 * the manifest receiver works as soon as config is saved and RECEIVE_SMS is
 * granted.
 *
 * Note: this app does NOT request READ_SMS. It only needs RECEIVE_SMS (to wake
 * on the incoming-SMS broadcast) and SEND_SMS (for SMS->SMS mode).
 */
class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* result ignored here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(
                        prefs = Prefs(this),
                        isIgnoringBatteryOptimizations = { isIgnoringBatteryOptimizations() },
                        onRequestBatteryExemption = { requestBatteryExemption() },
                        isSmsPermissionGranted = { isReceiveSmsGranted() },
                    )
                }
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
            // Fall back to the general battery-optimization settings list.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsScreen(
    prefs: Prefs,
    isIgnoringBatteryOptimizations: () -> Boolean,
    onRequestBatteryExemption: () -> Unit,
    isSmsPermissionGranted: () -> Boolean,
) {
    var mode by remember { mutableStateOf(prefs.mode) }

    // Email fields (Resend)
    var apiKey by remember { mutableStateOf(prefs.resendApiKey) }
    var from by remember { mutableStateOf(prefs.resendFrom) }
    var to by remember { mutableStateOf(prefs.emailTo) }
    var showKey by remember { mutableStateOf(false) }

    // SMS field
    var dest by remember { mutableStateOf(prefs.destNumber) }

    // Filters
    var senders by remember { mutableStateOf(prefs.senderAllowList) }
    var keyword by remember { mutableStateOf(prefs.keyword) }
    var regex by remember { mutableStateOf(prefs.regex) }

    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text("SMS Forwarder", style = MaterialTheme.typography.titleLarge)

        // --- Status line ---
        val smsOk = isSmsPermissionGranted()
        val battOk = isIgnoringBatteryOptimizations()
        Text(
            buildString {
                append(if (smsOk) "✓ SMS permission" else "✗ SMS permission NOT granted")
                append("   ")
                append(if (battOk) "✓ Battery unrestricted" else "✗ Battery optimized (may delay)")
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        // --- Mode toggle ---
        Text("Forward via", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == Prefs.Mode.EMAIL,
                onClick = { mode = Prefs.Mode.EMAIL },
                label = { Text("Email") }
            )
            FilterChip(
                selected = mode == Prefs.Mode.SMS,
                onClick = { mode = Prefs.Mode.SMS },
                label = { Text("SMS") }
            )
            FilterChip(
                selected = mode == Prefs.Mode.BOTH,
                onClick = { mode = Prefs.Mode.BOTH },
                label = { Text("Both") }
            )
        }

        val showEmail = mode == Prefs.Mode.EMAIL || mode == Prefs.Mode.BOTH
        val showSms = mode == Prefs.Mode.SMS || mode == Prefs.Mode.BOTH

        // --- Per-mode destination fields ---
        if (showEmail) {
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("Resend API key (re_…)") }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = from, onValueChange = { from = it },
                label = { Text("From address") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("Forward to (email)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "With the default From (onboarding@resend.dev), Resend only delivers " +
                    "to the email your Resend account is registered under.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (showSms) {
            OutlinedTextField(
                value = dest, onValueChange = { dest = it },
                label = { Text("Forward to (phone number)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Carrier SMS charges may apply per forwarded text.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Filters (all optional — blank = forward everything)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = senders, onValueChange = { senders = it },
            label = { Text("Only these senders (comma-separated)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = keyword, onValueChange = { keyword = it },
            label = { Text("Body must contain keyword") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = regex, onValueChange = { regex = it },
            label = { Text("Body must match regex") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            modifier = Modifier.padding(top = 16.dp),
            onClick = {
                prefs.mode = mode
                if (showEmail) {
                    prefs.resendApiKey = apiKey.trim()
                    prefs.resendFrom = from.trim().ifBlank { Prefs.DEFAULT_FROM }
                    prefs.emailTo = to.trim()
                }
                if (showSms) {
                    prefs.destNumber = dest.trim()
                }
                prefs.senderAllowList = senders.trim()
                prefs.keyword = keyword.trim()
                prefs.regex = regex.trim()
                saved = true
            }
        ) { Text("Save") }

        if (saved) {
            Text(
                "Saved. Forwarding is active.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        OutlinedButton(
            modifier = Modifier.padding(top = 16.dp),
            onClick = onRequestBatteryExemption
        ) { Text("Disable battery optimization") }
    }
}
