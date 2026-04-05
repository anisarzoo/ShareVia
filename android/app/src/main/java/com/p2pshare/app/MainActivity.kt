package com.ShareVia.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: ShareViaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ShareViaApp(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareViaApp(viewModel: ShareViaViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingEndpointForSend by rememberSaveable { mutableStateOf<String?>(null) }
    var draftProfileName by rememberSaveable(uiState.profile.displayName) {
        mutableStateOf(uiState.profile.displayName)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantMap ->
            val blocked = grantMap.filterValues { granted -> !granted }.keys
            if (blocked.isEmpty()) {
                viewModel.startNearbySession()
            } else {
                viewModel.pushMessage("Nearby permissions denied. Offline discovery cannot start yet.")
            }
        }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val endpoint = pendingEndpointForSend
            pendingEndpointForSend = null
            if (uri == null || endpoint == null) {
                return@rememberLauncherForActivityResult
            }

            viewModel.sendFile(endpoint, uri)
        }

    val avatarPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            viewModel.updateAvatar(uri)
        }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ShareVia",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    text = "Native offline and online sharing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                DrawerItem(
                    label = "Home",
                    selected = uiState.destination == DrawerDestination.HOME,
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    onClick = {
                        viewModel.changeDestination(DrawerDestination.HOME)
                        scope.launch { drawerState.close() }
                    },
                )
                DrawerItem(
                    label = "Profile",
                    selected = uiState.destination == DrawerDestination.PROFILE,
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = {
                        viewModel.changeDestination(DrawerDestination.PROFILE)
                        scope.launch { drawerState.close() }
                    },
                )
                DrawerItem(
                    label = "History",
                    selected = uiState.destination == DrawerDestination.HISTORY,
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    onClick = {
                        viewModel.changeDestination(DrawerDestination.HISTORY)
                        scope.launch { drawerState.close() }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    AvatarCircle(
                        displayName = uiState.profile.displayName,
                        avatarUri = uiState.profile.avatarUri,
                        size = 42.dp,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Column {
                        Text(uiState.profile.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (uiState.isNearbySessionActive) "Nearby active" else "Nearby paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (uiState.destination) {
                                DrawerDestination.HOME -> "Nearby Share"
                                DrawerDestination.PROFILE -> "Profile"
                                DrawerDestination.HISTORY -> "Activity History"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            when (uiState.destination) {
                DrawerDestination.HOME -> {
                    HomeScreen(
                        state = uiState,
                        onStartNearbyClicked = {
                            val missing = nearbyPermissions().filterNot { permission ->
                                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isEmpty()) {
                                viewModel.startNearbySession()
                            } else {
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        },
                        onStopNearbyClicked = { viewModel.stopNearbySession() },
                        onConnectClicked = { endpointId -> viewModel.connect(endpointId) },
                        onSendFileClicked = { endpointId ->
                            pendingEndpointForSend = endpointId
                            filePicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                DrawerDestination.PROFILE -> {
                    ProfileScreen(
                        profile = uiState.profile,
                        draftName = draftProfileName,
                        onDraftNameChanged = { draftProfileName = it },
                        onSaveClicked = { viewModel.saveProfileName(draftProfileName) },
                        onAvatarPickClicked = { avatarPicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                DrawerDestination.HISTORY -> {
                    HistoryScreen(
                        history = uiState.history,
                        onClearClicked = { viewModel.clearHistory() },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        icon = icon,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun HomeScreen(
    state: ShareViaUiState,
    onStartNearbyClicked: () -> Unit,
    onStopNearbyClicked: () -> Unit,
    onConnectClicked: (String) -> Unit,
    onSendFileClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, contentDescription = null)
                    Text(
                        "Offline-First Nearby",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Device discovery and transfer use Nearby P2P directly, so internet is optional.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Flight mode can still work if Bluetooth is manually enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStartNearbyClicked) {
                        Text(if (state.isNearbySessionActive) "Restart Nearby" else "Start Nearby")
                    }
                    OutlinedButton(onClick = onStopNearbyClicked, enabled = state.isNearbySessionActive) {
                        Text("Stop")
                    }
                }
            }
        }

        Text(
            text = "Status: ${state.statusMessage}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Nearby Devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.peers.isEmpty()) {
            EmptyLabel("No devices found yet. Keep both phones on this screen while Nearby is active.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(state.peers, key = { it.endpointId }) { peer ->
                    PeerCard(
                        peer = peer,
                        onConnectClicked = { onConnectClicked(peer.endpointId) },
                        onSendFileClicked = { onSendFileClicked(peer.endpointId) },
                    )
                }
            }
        }

        if (state.liveTransfers.isNotEmpty()) {
            Text(
                text = "Live Transfers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(state.liveTransfers.take(8), key = { it.transferId }) { transfer ->
                    TransferCard(transfer = transfer)
                }
            }
        } else {
            EmptyLabel("No active transfers.")
        }
    }
}

@Composable
private fun PeerCard(
    peer: NearbyPeer,
    onConnectClicked: () -> Unit,
    onSendFileClicked: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarCircle(displayName = peer.displayName, avatarUri = null, size = 38.dp)
                    Column {
                        Text(peer.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (peer.connected) "Connected" else "Discovered",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (peer.connected) Color(0xFF176C3A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = formatTimestamp(peer.lastSeenAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectClicked, enabled = !peer.connected) {
                    Text(if (peer.connected) "Connected" else "Pair")
                }
                OutlinedButton(onClick = onSendFileClicked, enabled = peer.connected) {
                    Text("Send File")
                }
            }
        }
    }
}

@Composable
private fun TransferCard(transfer: TransferItem) {
    val progress =
        if (transfer.totalBytes <= 0L) {
            0f
        } else {
            (transfer.transferredBytes.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(transfer.fileName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${transfer.peerName} - ${transfer.direction.displayLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${transfer.transferredBytes.toFileSizeLabel()} / ${transfer.totalBytes.toFileSizeLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    transfer.status.displayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (transfer.status) {
                        TransferStatus.COMPLETED -> Color(0xFF176C3A)
                        TransferStatus.FAILED -> Color(0xFFB3261E)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (transfer.status == TransferStatus.IN_PROGRESS || transfer.status == TransferStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    profile: ShareProfile,
    draftName: String,
    onDraftNameChanged: (String) -> Unit,
    onSaveClicked: () -> Unit,
    onAvatarPickClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Update how your phone appears when nearby devices search for you.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(displayName = draftName, avatarUri = profile.avatarUri, size = 64.dp)
            OutlinedButton(onClick = onAvatarPickClicked) {
                Text("Change DP")
            }
        }
        OutlinedTextField(
            value = draftName,
            onValueChange = onDraftNameChanged,
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSaveClicked, enabled = draftName.isNotBlank()) {
            Text("Save Profile")
        }
    }
}

@Composable
private fun HistoryScreen(
    history: List<TransferHistoryEntry>,
    onClearClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onClearClicked, enabled = history.isNotEmpty()) {
                Text("Clear")
            }
        }
        if (history.isEmpty()) {
            EmptyLabel("No transfer history yet.")
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history, key = { it.id }) { entry ->
                Card {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(entry.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${entry.peerName} - ${entry.direction.displayLabel} - ${entry.sizeBytes.toFileSizeLabel()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTimestamp(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLabel(label: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AvatarCircle(displayName: String, avatarUri: Uri?, size: androidx.compose.ui.unit.Dp) {
    val initials = displayName.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "S" }
    if (avatarUri != null) {
        AsyncImage(
            model = avatarUri,
            contentDescription = "Profile picture",
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape),
        )
        return
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun nearbyPermissions(): List<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
        permissions += Manifest.permission.BLUETOOTH_ADVERTISE
    } else {
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.NEARBY_WIFI_DEVICES
    }

    return permissions.distinct()
}

private fun Long.toFileSizeLabel(): String {
    if (this <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        this >= gb.toLong() -> String.format(Locale.US, "%.2f GB", this / gb)
        this >= mb.toLong() -> String.format(Locale.US, "%.2f MB", this / mb)
        this >= kb.toLong() -> String.format(Locale.US, "%.1f KB", this / kb)
        else -> "$this B"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

