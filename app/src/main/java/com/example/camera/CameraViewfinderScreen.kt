package com.example.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Full-screen, real-time camera viewfinder interface powered by CameraX PreviewView.
 *
 * Features:
 * - Edge-to-edge PreviewView with real-time video stream
 * - Tap-to-focus with animated focusing reticle & CameraX MeteringPointFactory
 * - Pinch-to-zoom & quick zoom factor toggles (1x, 2x, 5x)
 * - Lens switching (front / rear) with smooth rotation animation
 * - Torch / flashlight toggle
 * - Rule-of-thirds composition grid overlay
 * - Shutter capture with visual flash feedback & MediaStore integration
 * - Quick preview thumbnail of captured photos
 * - Real-time viewfinder HUD status badges
 */
@Composable
fun CameraViewfinderScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission is required for the viewfinder", Toast.LENGTH_SHORT).show()
        }
    }

    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var isFrontCamera by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var isGridVisible by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var shutterFlashAlpha by remember { mutableFloatStateOf(0f) }

    // Camera control references
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // Zoom state
    var zoomRatio by remember { mutableFloatStateOf(1.0f) }
    var minZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var maxZoomRatio by remember { mutableFloatStateOf(8.0f) }

    // Focus state
    var focusTapPosition by remember { mutableStateOf<Offset?>(null) }
    var isFocusing by remember { mutableStateOf(false) }

    // Last captured photo
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }
    var showCapturedPreviewDialog by remember { mutableStateOf(false) }

    // Shutter button rotation animation when switching cameras
    var flipRotation by remember { mutableFloatStateOf(0f) }
    val animatedFlipRotation by animateFloatAsState(
        targetValue = flipRotation,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "flipRotation"
    )

    // LIVE pulsing indicator animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulseLive")
    val liveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveAlpha"
    )

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // Fullscreen Viewfinder Container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_viewfinder_container")
    ) {
        if (hasCameraPermission) {
            // 1. CameraX PreviewView filling the full screen
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("camerax_preview_view"),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    }.also { previewViewRef = it }
                },
                update = { previewView ->
                    previewViewRef = previewView
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCapture = capture

                            cameraProvider.unbindAll()
                            val camera: Camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                capture
                            )
                            cameraControl = camera.cameraControl
                            cameraInfo = camera.cameraInfo

                            // Observe camera zoom state
                            camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                                state?.let {
                                    zoomRatio = it.zoomRatio
                                    minZoomRatio = it.minZoomRatio
                                    maxZoomRatio = it.maxZoomRatio.coerceAtMost(8.0f)
                                }
                            }

                            cameraControl?.enableTorch(isTorchOn)
                        } catch (e: Exception) {
                            Toast.makeText(previewView.context, "Viewfinder error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }, ContextCompat.getMainExecutor(previewView.context))
                }
            )

            // 2. Viewfinder Gesture Layer: Tap-to-Focus & Pinch-to-Zoom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cameraControl, minZoomRatio, maxZoomRatio) {
                        detectTransformGestures { _, _, zoomFactor, _ ->
                            val targetZoom = (zoomRatio * zoomFactor).coerceIn(minZoomRatio, maxZoomRatio)
                            zoomRatio = targetZoom
                            cameraControl?.setZoomRatio(targetZoom)
                        }
                    }
                    .pointerInput(cameraControl, previewViewRef) {
                        detectTapGestures { offset ->
                            focusTapPosition = offset
                            isFocusing = true

                            previewViewRef?.let { pv ->
                                try {
                                    val factory = pv.meteringPointFactory
                                    val point = factory.createPoint(offset.x, offset.y)
                                    val action = FocusMeteringAction.Builder(
                                        point,
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                    )
                                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                                        .build()
                                    cameraControl?.startFocusAndMetering(action)
                                } catch (e: Exception) {
                                    // Ignore metering error on edges
                                }
                            }

                            scope.launch {
                                delay(1600)
                                isFocusing = false
                                focusTapPosition = null
                            }
                        }
                    }
            )

            // 3. Composition Grid Overlay (Rule of Thirds)
            if (isGridVisible) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridColor = Color.White.copy(alpha = 0.35f)
                    val stroke = Stroke(width = 1.dp.toPx())

                    // Vertical third lines
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = stroke.width)
                    drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = stroke.width)

                    // Horizontal third lines
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = stroke.width)
                    drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = stroke.width)

                    // Center crosshair
                    val cx = w / 2f
                    val cy = h / 2f
                    val chSize = 12.dp.toPx()
                    val centerColor = Color.Yellow.copy(alpha = 0.5f)
                    drawLine(centerColor, Offset(cx - chSize, cy), Offset(cx + chSize, cy), strokeWidth = 1.5f)
                    drawLine(centerColor, Offset(cx, cy - chSize), Offset(cx, cy + chSize), strokeWidth = 1.5f)
                }
            }

            // 4. Animated Focus Target Reticle
            focusTapPosition?.let { pos ->
                if (isFocusing) {
                    val focusScale by animateFloatAsState(
                        targetValue = 0.9f,
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                        label = "focusScale"
                    )

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - 36.dp.toPx()).roundToInt(),
                                    (pos.y - 36.dp.toPx()).roundToInt()
                                )
                            }
                            .size(72.dp)
                            .border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                    ) {
                        // Small center target indicator
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(Color(0xFFFFD54F), CircleShape)
                                .align(Alignment.Center)
                        )
                    }
                }
            }

            // 5. Visual Shutter Flash Effect
            if (shutterFlashAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = shutterFlashAlpha))
                )
            }

            // 6. Top Translucent HUD & Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .testTag("viewfinder_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                // Center LIVE HUD Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(liveAlpha)
                                .background(Color(0xFF00E676), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LIVE VIEWFINDER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFrontCamera) "FRONT" else "REAR",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        )
                    }
                }

                // Top Right Action Buttons: Grid & Torch
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Grid Toggle Button
                    IconButton(
                        onClick = { isGridVisible = !isGridVisible },
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (isGridVisible) Color(0xFF2979FF) else Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .testTag("viewfinder_grid_button")
                    ) {
                        Icon(
                            imageVector = if (isGridVisible) Icons.Default.Grid3x3 else Icons.Default.GridOff,
                            contentDescription = "Toggle Grid",
                            tint = Color.White
                        )
                    }

                    // Flashlight / Torch Button (Available on rear camera)
                    IconButton(
                        onClick = {
                            if (!isFrontCamera) {
                                val newTorch = !isTorchOn
                                isTorchOn = newTorch
                                cameraControl?.enableTorch(newTorch)
                            } else {
                                Toast.makeText(context, "Flashlight unavailable on front camera", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (isTorchOn) Color(0xFFFFC107) else Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .testTag("viewfinder_torch_button")
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Flashlight",
                            tint = if (isTorchOn) Color.Black else Color.White
                        )
                    }
                }
            }

            // 7. Quick Zoom Selector Pills (Floating above bottom controls)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val zoomSteps = listOf(1.0f, 2.0f, 5.0f)
                zoomSteps.forEach { step ->
                    val isSelected = (zoomRatio - step) in -0.3f..0.3f
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Color(0xFFFFC107) else Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier
                            .clickable {
                                zoomRatio = step
                                cameraControl?.setZoomRatio(step)
                            }
                            .testTag("viewfinder_zoom_${step.toInt()}x")
                    ) {
                        Text(
                            text = "${step.toInt()}x",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) Color.Black else Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 8. Bottom Control Bar (Thumbnail, Shutter, Flip Camera)
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Captured Photo Thumbnail Button
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            .clickable(enabled = lastCapturedUri != null) {
                                showCapturedPreviewDialog = true
                            }
                            .testTag("viewfinder_gallery_thumbnail_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lastCapturedUri != null) {
                            AsyncImage(
                                model = lastCapturedUri,
                                contentDescription = "Last captured photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Photo,
                                contentDescription = "No photo yet",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Center: Shutter Capture Button
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(if (isCapturing) Color.LightGray else Color.White)
                            .clickable(enabled = !isCapturing) {
                                val capture = imageCapture
                                if (capture != null && !isCapturing) {
                                    isCapturing = true

                                    // Trigger shutter flash
                                    scope.launch {
                                        shutterFlashAlpha = 0.85f
                                        delay(80)
                                        shutterFlashAlpha = 0f
                                    }

                                    capturePhoto(context, capture, cameraExecutor) { success, uri, msg ->
                                        isCapturing = false
                                        if (success && uri != null) {
                                            lastCapturedUri = uri
                                            Toast.makeText(context, "Photo captured!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            .testTag("viewfinder_shutter_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Capture Photo",
                                tint = Color.Black,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    // Right: Switch Camera Facing Button (Lens Switch)
                    IconButton(
                        onClick = {
                            flipRotation += 180f
                            isFrontCamera = !isFrontCamera
                            cameraSelector = if (isFrontCamera) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                            isTorchOn = false
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .rotate(animatedFlipRotation)
                            .testTag("viewfinder_switch_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera Facing",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        } else {
            // Permission Request State
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Camera Viewfinder Permission",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "The live camera viewfinder requires access to the camera to render the real-time preview and capture photos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("grant_camera_permission_button")
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Camera Viewfinder", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                ) {
                    Text("Go Back", color = Color.White)
                }
            }
        }

        // Fullscreen Photo Preview Dialog for recently captured image
        if (showCapturedPreviewDialog && lastCapturedUri != null) {
            Dialog(
                onDismissRequest = { showCapturedPreviewDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AsyncImage(
                        model = lastCapturedUri,
                        contentDescription = "Captured Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Close preview button
                    IconButton(
                        onClick = { showCapturedPreviewDialog = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Preview", tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Saves photo to MediaStore Pictures/FamilyWellbeing using CameraX ImageCapture.
 */
private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onResult: (Boolean, Uri?, String) -> Unit
) {
    val name = "Viewfinder_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FamilyWellbeing")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                ContextCompat.getMainExecutor(context).execute {
                    onResult(false, null, "Capture failed: ${exc.message}")
                }
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                ContextCompat.getMainExecutor(context).execute {
                    val savedUri = output.savedUri
                    onResult(true, savedUri, "Photo saved successfully!")
                }
            }
        }
    )
}
