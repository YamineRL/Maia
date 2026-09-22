package dev.maia.tunnel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The notification is the only place the tunnel is visible once the
        // user has switched to Termius, so ask for it up front rather than at
        // the moment of connecting.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = themeColors()) {
                TunnelScreen()
            }
        }
    }
}

@Composable
private fun themeColors(): ColorScheme {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen() {
    val context = LocalContext.current
    val state by Tunnel.state.collectAsStateWithLifecycle()
    val config = remember { Config(context) }

    var address by remember { mutableStateOf(config.address) }
    var forwards by remember { mutableStateOf(config.forwards) }
    var debug by remember { mutableStateOf(config.debug) }
    var showPair by remember { mutableStateOf(config.address.isEmpty()) }
    var showAddForward by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Tunnel.publicKey(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maia Tunnel") },
                actions = {
                    IconButton(onClick = { showPair = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Devbox settings",
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinkCard(
                state = state,
                paired = config.isPaired,
                onToggle = { on ->
                    if (on) TunnelService.start(context) else TunnelService.stop(context)
                },
                onPing = { Tunnel.ping() },
            )

            if (state.error.isNotEmpty()) {
                ErrorCard(state.error)
            }

            ForwardsCard(
                specs = forwards,
                live = state.forwards,
                editable = state.link == LinkState.OFF,
                onRemove = { spec ->
                    forwards = forwards.filterNot { it == spec }
                    config.forwards = forwards
                },
                onAdd = { showAddForward = true },
            )

            val agentSpec = AgentLink.configured(forwards)
            val agentUrl = AgentLink.url(state, agentSpec)
            var agentError by remember { mutableStateOf<String?>(null) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("oc-fusion", style = MaterialTheme.typography.titleMedium)
                    Text("Continue an OpenCode session on your devbox. Prompts, replies and tool output stay in that session.")
                    Text("Requires an OpenCode web server started with a password, and port 4096 allowed by the devbox tunnel. The password is what stops other apps on this phone reaching it. This does not grant phone screen control.")
                    if (agentSpec == null) {
                        Button(
                            enabled = state.link == LinkState.OFF && AgentLink.canAdd(forwards),
                            onClick = {
                                forwards = forwards + AgentLink.preset()
                                config.forwards = forwards
                            },
                        ) { Text("Add agent forward") }
                        if (!AgentLink.canAdd(forwards)) {
                            Text("Port 4096 is already mapped. Edit that forward first.")
                        } else if (state.link != LinkState.OFF) {
                            Text("Disconnect the tunnel to add a forward.")
                        }
                    } else {
                        // M8 decision 13.3: this button stays, and it is
                        // labelled as what it is. It is the way in when Maia's
                        // own agent screen is not working, not the ordinary
                        // way to talk to an agent, so it is an outlined button
                        // rather than a filled one and it carries a caption
                        // saying what it is for and what it actually does.
                        OutlinedButton(enabled = agentUrl != null, onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(agentUrl!!)))
                                agentError = null
                            } catch (_: android.content.ActivityNotFoundException) {
                                agentError = "No browser is available to open the agent."
                            }
                        }) { Text("Open the agent in a browser") }
                        Text(
                            "A way in when Maia's own agent screen is not working. It opens the web app on your machine.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (agentUrl == null) Text("Connect the tunnel before opening the agent.")
                    }
                    agentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            DeviceKeyCard(state.publicKey)

            DebugRow(debug) {
                debug = it
                config.debug = it
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPair) {
        PairSheet(
            initial = address,
            onDismiss = { showPair = false },
            onSave = {
                address = it
                config.address = it
                showPair = false
            },
        )
    }

    if (showAddForward) {
        AddForwardSheet(
            existing = forwards,
            onDismiss = { showAddForward = false },
            onAdd = { spec ->
                forwards = forwards + spec
                config.forwards = forwards
                showAddForward = false
            },
        )
    }
}
