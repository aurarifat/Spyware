package com.example.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.common.AppResult
import com.example.core.common.SafeLogger
import com.example.data.p2p.P2PNetworkUtils
import com.example.data.p2p.ParentP2PClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen used by a PARENT to remotely view and control the CHILD device's camera stream.
 * Does NOT open the parent's local camera. Instead, decodes and renders real-time frames
 * sent by the Child device over the peer-to-peer connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteCameraViewerScreen(
    deviceId: String,
    parentUid: String,
    initialChildHost: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ParentP2PClient.getInstance(context) }

    // Resolve Child target address
    var targetAddress by remember {
        val registered = client.getDeviceAddress(deviceId)
        val initial = when {
            !registered.isNullOrBlank() -> registered
            !initialChildHost.isNullOrBlank() -> initialChildHost
            else -> "127.0.0.1:${P2PNetworkUtils.DEFAULT_PORT}"
        }
        mutableStateOf(initial)
    }

    var currentFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isFrontCamera by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var fps by remember { mutableFloatStateOf(0f) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var inputAddress by remember { mutableStateOf(targetAddress) }

    // Pulsing animation for LIVE streaming badge
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Start stream command and frame receiver loop
    LaunchedEffect(targetAddress) {
        isConnecting = true
        errorMessage = null

        // 1. Send start command to child
        SafeLogger.i("RemoteCamera", "Sending start camera stream command to $targetAddress")
        val startRes = client.sendCameraControl(targetAddress, "start")
        if (startRes is AppResult.Error) {
            SafeLogger.w("RemoteCamera", "Start stream request failed: ${startRes.errorOrNull()}")
        }

        // 2. Continuous frame polling loop (~15 FPS)
        var frameCount = 0
        var lastFpsTime = System.currentTimeMillis()
        var failedConsecutive = 0

        while (isActive) {
            val bytes = client.fetchCameraFrame(targetAddress)
            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = withContext(Dispatchers.Default) {
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    currentFrameBitmap = bitmap
                    isConnected = true
                    isConnecting = false
                    failedConsecutive = 0
                    frameCount++

                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        fps = (frameCount * 1000f) / (now - lastFpsTime)
                        frameCount = 0
                        lastFpsTime = now
                    }
                }
            } else {
                failedConsecutive++
                if (failedConsecutive > 25 && !isConnected) {
                    isConnecting = false
                    errorMessage = "Cannot connect to Child Camera at $targetAddress.\nEnsure both devices are on the same Wi-Fi network and Child mode is open."
                }
            }
            delay(66) // ~15 FPS target
        }
    }

    // Cleanup: tell child to stop streaming when leaving this screen
    DisposableEffect(targetAddress) {
        onDispose {
            scope.launch(Dispatchers.IO) {
                SafeLogger.i("RemoteCamera", "Stopping remote camera on $targetAddress")
                client.sendCameraControl(targetAddress, "stop")
            }
        }
    }

    if (showAddressDialog) {
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text("Configure Child Device IP") },
            text = {
                Column {
                    Text(
                        "Enter the IP address and port shown on the child's screen (e.g. 192.168.1.105:8888):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputAddress,
                        onValueChange = { inputAddress = it },
                        label = { Text("Child IP:Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputAddress.isNotBlank()) {
                            targetAddress = inputAddress.trim()
                            client.registerDeviceAddress(deviceId, inputAddress.trim())
                            showAddressDialog = false
                        }
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Remote Child Camera",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isConnected) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.alpha(pulseAlpha)
                                ) {
                                    Text(
                                        text = "LIVE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (isConnected) "Streaming from $targetAddress (%.0f FPS)".format(fps) else "Target: $targetAddress",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddressDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Change Connection Address")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Live Video Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                val frame = currentFrameBitmap
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Live video from child device",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay stream status
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color(0x80000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF00E676), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Child Camera: ${if (isFrontCamera) "Front" else "Rear"}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else if (isConnecting) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connecting to Child Camera...",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Awaiting live video frames from $targetAddress",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Connection error / offline state
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Child Device Camera Offline",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "Unable to establish direct video connection with child device.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row {
                            Button(
                                onClick = { showAddressDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check / Change IP")
                            }
                        }
                    }
                }
            }

            // Bottom Remote Controls Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Control Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Switch Camera Button
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val res = client.sendCameraControl(targetAddress, "switch")
                                    if (res is AppResult.Success) {
                                        isFrontCamera = res.data.optBoolean("isFrontCamera", !isFrontCamera)
                                        Toast.makeText(context, "Switched child camera", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = isConnected,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isFrontCamera) "Rear Lens" else "Front Lens", fontSize = 12.sp)
                        }

                        // Remote Torch Toggle
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val newTorch = !isTorchOn
                                    val res = client.sendCameraControl(targetAddress, "torch", mapOf("enabled" to newTorch))
                                    if (res is AppResult.Success) {
                                        isTorchOn = newTorch
                                        Toast.makeText(context, if (newTorch) "Child torch turned ON" else "Child torch turned OFF", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = isConnected && !isFrontCamera,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (isTorchOn) Icons.Default.FlashOff else Icons.Default.FlashOn,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isTorchOn) "Torch Off" else "Torch On", fontSize = 12.sp)
                        }

                        // Save Remote Snapshot
                        FilledTonalButton(
                            onClick = {
                                val frame = currentFrameBitmap
                                if (frame != null) {
                                    saveSnapshotToGallery(context, frame)
                                }
                            },
                            enabled = isConnected && currentFrameBitmap != null,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Snapshot", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Transparent supervision notice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Supervision active with child consent. Live camera streaming from child's phone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun saveSnapshotToGallery(context: Context, bitmap: Bitmap) {
    try {
        val filename = "Child_Safety_Snapshot_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FamilyWellbeing")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
            Toast.makeText(context, "Snapshot saved to Gallery!", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Failed saving snapshot: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
