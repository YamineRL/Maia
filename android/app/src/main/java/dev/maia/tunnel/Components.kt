package dev.maia.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** The switch, the way WireGuard does it: one big obvious on/off. */
@Composable
fun LinkCard(
    state: TunnelState,
    paired: Boolean,
    onToggle: (Boolean) -> Unit,
    onPing: () -> Unit,
) {
    val on = state.link != LinkState.OFF
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.link == LinkState.ON) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Devbox", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (state.link) {
                            LinkState.ON -> "Connected"
                            LinkState.CONNECTING -> "Connecting"
                            LinkState.OFF -> if (paired) "Off" else "Not paired yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.link == LinkState.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Switch(checked = on, enabled = paired, onCheckedChange = onToggle)
            }

            if (state.link == LinkState.ON) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.pingMs > 0) "Round trip ${state.pingMs} ms" else "Round trip not measured",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPing) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Measure")
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            message,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
fun ForwardsCard(
    specs: List<ForwardSpec>,
    live: List<ForwardStatus>,
    editable: Boolean,
    onRemove: (ForwardSpec) -> Unit,
    onAdd: () -> Unit,
) {
    OutlinedCard {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Forwards",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add a forward") }
                }
            }

            specs.forEach { spec ->
                val status = live.firstOrNull { it.remotePort == spec.remotePort }
                ForwardRow(spec, status, editable) { onRemove(spec) }
            }

            if (!editable) {
                Text(
                    "Turn the tunnel off to change forwards.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ForwardRow(
    spec: ForwardSpec,
    status: ForwardStatus?,
    editable: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(spec.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "127.0.0.1:${spec.localPort}  ->  devbox:${spec.remotePort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != null) {
                val open = if (status.openConns > 0) "${status.openConns} open  " else ""
                Text(
                    "$open${bytes(status.bytesIn)} in  ${bytes(status.bytesOut)} out",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!status?.lastError.isNullOrEmpty()) {
                Text(
                    status.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (editable) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove ${spec.name}") }
        }
    }
}

/** The string the devbox operator has to add to the server's --allow list. */
@Composable
fun DeviceKeyCard(publicKey: String) {
    val context = LocalContext.current
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This device's key", style = MaterialTheme.typography.titleMedium)
            Text(
                "The devbox only accepts clients it was started with. Add this to the tailcat server's --allow list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                publicKey.ifEmpty { "generating" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { copy(context, "node key", publicKey) },
                    enabled = publicKey.isNotEmpty(),
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
            }
        }
    }
}

@Composable
fun DebugRow(debug: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Verbose logging", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Logs magicsock routing to logcat, which is the only way to tell a direct path from a relay. Noisy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = debug, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairSheet(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var reveal by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Devbox address", style = MaterialTheme.typography.titleLarge)
            Text(
                "Paste the address printed by tailcat serve on the devbox. It contains the encryption key, so treat the whole string as a password: anyone holding it can reach the ports you serve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("tailcat address") },
                singleLine = false,
                minLines = 2,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (reveal) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { reveal = !reveal }) {
                    Text(if (reveal) "Hide" else "Show")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                    Text("Save")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddForwardSheet(
    existing: List<ForwardSpec>,
    onDismiss: () -> Unit,
    onAdd: (ForwardSpec) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var local by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }

    val localPort = local.toIntOrNull()
    val remotePort = remote.toIntOrNull()
    val clash = localPort != null && existing.any { it.localPort == localPort }
    val valid = name.isNotBlank() &&
        localPort in 1024..65535 &&
        remotePort in 1..65535 &&
        !clash

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add a forward", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = local,
                onValueChange = { local = it.filter(Char::isDigit).take(5) },
                label = { Text("Local port on this phone") },
                supportingText = {
                    Text(
                        when {
                            clash -> "Already used by another forward"
                            else -> "1024 or above. Ports below that need root."
                        }
                    )
                },
                isError = clash,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = remote,
                onValueChange = { remote = it.filter(Char::isDigit).take(5) },
                label = { Text("Port on the devbox") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onAdd(ForwardSpec(name.trim(), localPort!!, remotePort!!)) },
                    enabled = valid,
                ) { Text("Add") }
            }
        }
    }
}

private fun copy(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(Locale.US, "%.1f kB", n / 1024.0)
    n < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", n / (1024.0 * 1024 * 1024))
}
