package com.klabcc.piersocial.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.klabcc.piersocial.mesh.MeshController
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.MeshViolet
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary

@Composable
fun PermissionsScreen(
    fingerprint: String,
    handle: String,
    developerMode: Boolean = false,
    onContinue: (startMesh: Boolean) -> Unit
) {
    val context = LocalContext.current
    var checkVersion by remember { mutableIntStateOf(0) }
    val requiredPerms = remember { MeshController.requiredPermissions() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkVersion++
        if (MeshController.hasRequiredMeshPermissions(context)) {
            onContinue(true)
        }
    }

    val allBleGranted = remember(checkVersion) {
        MeshController.hasRequiredMeshPermissions(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MeshCyan, MeshViolet))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothConnected,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = "Welcome to PierSocial",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (developerMode) {
                    "A decentralized, server-free photo & DM network powered by Bluetooth Low Energy mesh gossip and your hardware-backed cryptographic identity."
                } else {
                    "Share photos, stories, and private messages directly with people nearby using Bluetooth—no internet or servers required."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            if (developerMode) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MeshCyan)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Local Identity Ready • $handle",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Ed25519 / X25519 keys secured in Android Keystore\n$fingerprint",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Text(
                text = "Required Permissions",
                style = MaterialTheme.typography.titleMedium,
                color = MeshCyan
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionRow(
                    icon = Icons.Default.BluetoothConnected,
                    title = "Nearby Bluetooth Discovery",
                    subtitle = if (developerMode) "BLUETOOTH_SCAN & BLUETOOTH_ADVERTISE for peer discovery"
                    else "Find nearby PierSocial users over Bluetooth",
                    granted = isGranted(context, Manifest.permission.BLUETOOTH_SCAN, checkVersion) &&
                        isGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE, checkVersion)
                )
                PermissionRow(
                    icon = Icons.Default.Shield,
                    title = "Bluetooth Connections",
                    subtitle = if (developerMode) "BLUETOOTH_CONNECT for MTU negotiation & chunk transfer"
                    else "Exchange photos, stories, and messages directly",
                    granted = isGranted(context, Manifest.permission.BLUETOOTH_CONNECT, checkVersion)
                )
            } else {
                PermissionRow(
                    icon = Icons.Default.BluetoothConnected,
                    title = "Bluetooth Discovery Access",
                    subtitle = "Required by Android 11 and older to find nearby Bluetooth devices",
                    granted = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION, checkVersion)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    icon = Icons.Default.Shield,
                    title = "Wi-Fi Direct High-Speed Sync",
                    subtitle = if (developerMode) "NEARBY_WIFI_DEVICES for seamless multi-Mbps P2P photo & audio bursts"
                    else "Optional high-speed Wi-Fi Direct upgrade for faster media transfers",
                    granted = isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES, checkVersion)
                )
                PermissionRow(
                    icon = Icons.Default.Notifications,
                    title = "DM Push Notifications & Sync",
                    subtitle = if (developerMode) "POST_NOTIFICATIONS enables DM push alerts & foreground relay status"
                    else "Instant push alerts for incoming DMs and background nearby sharing",
                    granted = isGranted(context, Manifest.permission.POST_NOTIFICATIONS, checkVersion)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionRow(
                    icon = Icons.Default.Shield,
                    title = "Wi-Fi Direct High-Speed Sync",
                    subtitle = if (developerMode) "ACCESS_FINE_LOCATION enables Wi-Fi P2P group transfer on Android 12"
                    else "Optional high-speed Wi-Fi Direct upgrade for faster media transfers",
                    granted = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION, checkVersion)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (allBleGranted) {
                        onContinue(true)
                    } else {
                        launcher.launch(requiredPerms)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MeshCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (allBleGranted) "Launch BLE Mesh" else "Enable BLE Mesh & Enter",
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(
                onClick = { onContinue(false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for Now", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = if (granted) SignalGreen else MeshCyan)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (granted) SignalGreen else TextSecondary
            )
        }
    }
}

private fun isGranted(context: android.content.Context, permission: String, @Suppress("UNUSED_PARAMETER") version: Int): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
