package com.example.qrsc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.qrsc.ui.theme.Accent
import com.example.qrsc.ui.theme.AccentGreen
import com.example.qrsc.ui.theme.AccentRed
import com.example.qrsc.ui.theme.Surface
import com.example.qrsc.ui.theme.SurfaceVariant
import com.example.qrsc.ui.theme.TextSecondary
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScannerTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val isScanning by viewModel.isScanning.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val isPreviewMode by viewModel.isPreviewMode.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    val lockedBitmap by viewModel.lockedBitmap.collectAsState()
    val countdownSetting by viewModel.countdownSetting.collectAsState()
    val countdownCurrent by viewModel.countdownCurrent.collectAsState()
    val isCountingDown by viewModel.isCountingDown.collectAsState()
    val downloadHint by viewModel.downloadHint.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val isCacheCapturing by ScannerState.isCacheCapturing.collectAsState()
    val cacheItems by viewModel.captureCacheItems.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (downloadHint.isNotEmpty() &&
                System.currentTimeMillis() - ScannerState.lastFileChunkTimeMs >= 10000) {
                viewModel.clearDownloadHint()
            }
        }
    }

    var showCachePicker by remember { mutableStateOf(false) }
    val isBlackScreenMode by ScannerState.isBlackScreen.collectAsState()
    var pendingScanStart by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingScanStart) {
            pendingScanStart = false
            viewModel.startScanning()
            context.startService(Intent(context, ScannerForegroundService::class.java))
        }
    }

    if (isBlackScreenMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val timedOut = withTimeoutOrNull(2000L) {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { !it.pressed }) return@withTimeoutOrNull false
                            }
                        }
                        if (timedOut == null) ScannerState.setIsBlackScreen(false)
                    }
                }
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = { viewModel.togglePreviewMode() },
                modifier = Modifier.fillMaxWidth(),
                enabled = isScanning,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isPreviewMode) "显示文本" else "查看画面", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isPreviewMode) {
                PreviewArea(
                    previewBitmap = previewBitmap,
                    lockedBitmap = lockedBitmap,
                    isCountingDown = isCountingDown,
                    countdownCurrent = countdownCurrent
                )
                Spacer(modifier = Modifier.height(8.dp))
                PreviewControls(
                    previewBitmap = previewBitmap,
                    lockedBitmap = lockedBitmap,
                    countdownSetting = countdownSetting,
                    isCountingDown = isCountingDown,
                    viewModel = viewModel
                )
            } else {
                TextArea(currentText = currentText, downloadHint = downloadHint)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isScanning) "● 正在采集" else "○ 等待采集…",
                color = if (isScanning) AccentGreen else TextSecondary,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isScanning) {
                        viewModel.stopScanning()
                        if (!isCacheCapturing) {
                            context.stopService(Intent(context, ScannerForegroundService::class.java))
                        }
                    } else {
                        if (!cameraPermissionState.status.isGranted) {
                            cameraPermissionState.launchPermissionRequest()
                            return@Button
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingScanStart = true
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@Button
                        }
                        viewModel.startScanning()
                        context.startService(Intent(context, ScannerForegroundService::class.java))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) AccentRed else AccentGreen),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isScanning) "停止采集" else "开始采集", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.toggleCamera()
                    val intent = Intent(context, ScannerForegroundService::class.java).apply {
                        action = ScannerForegroundService.ACTION_TOGGLE_CAMERA
                    }
                    context.startService(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isScanning && processingState is CaptureProcessingState.Idle,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isFrontCamera) "切换至后置采集源" else "切换至前置采集源", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))
            CaptureCacheControls(
                isScanning = isScanning,
                isCacheCapturing = isCacheCapturing,
                processingState = processingState,
                onToggleCapture = {
                    if (isCacheCapturing) viewModel.stopCachedCapture() else viewModel.startCachedCapture()
                },
                onShowCache = {
                    viewModel.refreshCaptureCacheItems()
                    showCachePicker = true
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { ScannerState.setIsBlackScreen(true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("进入低显模式", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }

            Text(
                text = "低显模式下长按 2 秒恢复界面",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
        }

        ProcessingOverlay(processingState, viewModel)
    }

    if (showCachePicker) {
        CachePickerDialog(
            items = cacheItems,
            onDismiss = { showCachePicker = false },
            onSelect = { item ->
                showCachePicker = false
                viewModel.processCacheItem(item)
            },
            onDelete = { item -> viewModel.deleteCaptureCacheItem(item) },
            onDeleteAll = {
                viewModel.deleteAllCaptureCache()
                showCachePicker = false
            }
        )
    }
}

@Composable
private fun PreviewArea(
    previewBitmap: android.graphics.Bitmap?,
    lockedBitmap: android.graphics.Bitmap?,
    isCountingDown: Boolean,
    countdownCurrent: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface),
        contentAlignment = Alignment.Center
    ) {
        val displayBitmap = lockedBitmap ?: previewBitmap
        if (displayBitmap != null) {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = "采集预览",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("等待画面…", color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(48.dp))
        }
        if (isCountingDown) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text("$countdownCurrent", style = MaterialTheme.typography.displayLarge, color = Color.White)
            }
        }
    }
}

@Composable
private fun PreviewControls(
    previewBitmap: android.graphics.Bitmap?,
    lockedBitmap: android.graphics.Bitmap?,
    countdownSetting: Int,
    isCountingDown: Boolean,
    viewModel: MainViewModel
) {
    if (isCountingDown) {
        Button(
            onClick = { viewModel.cancelCountdown() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            shape = RoundedCornerShape(8.dp)
        ) { Text("取消采集") }
    } else if (lockedBitmap != null) {
        Button(
            onClick = { viewModel.unlockPreview() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(8.dp)
        ) { Text("解锁画面") }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("延迟采集:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Button(
                onClick = { viewModel.setCountdownSetting(countdownSetting - 1) },
                enabled = countdownSetting > 1,
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                shape = RoundedCornerShape(8.dp)
            ) { Text("−", style = MaterialTheme.typography.titleMedium) }
            Text("${countdownSetting}秒", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(48.dp), textAlign = TextAlign.Center)
            Button(
                onClick = { viewModel.setCountdownSetting(countdownSetting + 1) },
                enabled = countdownSetting < 10,
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                shape = RoundedCornerShape(8.dp)
            ) { Text("+", style = MaterialTheme.typography.titleMedium) }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { viewModel.startCountdown() },
                enabled = previewBitmap != null,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                shape = RoundedCornerShape(8.dp)
            ) { Text("锁定", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun TextArea(currentText: String, downloadHint: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
    ) {
        val shownText = when {
            downloadHint.isNotEmpty() && currentText.isEmpty() -> downloadHint
            currentText.isEmpty() -> "等待采集…"
            else -> currentText
        }
        Text(
            text = shownText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp),
            color = if (currentText.isEmpty()) TextSecondary else MaterialTheme.colorScheme.onSurface,
            textAlign = if (currentText.isEmpty()) TextAlign.Center else TextAlign.Start,
            minLines = 8
        )
    }
}

@Composable
private fun CaptureCacheControls(
    isScanning: Boolean,
    isCacheCapturing: Boolean,
    processingState: CaptureProcessingState,
    onToggleCapture: () -> Unit,
    onShowCache: () -> Unit
) {
    val isCapturing = isCacheCapturing
    val isProcessing = processingState is CaptureProcessingState.Processing
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onToggleCapture,
            modifier = Modifier.weight(1f),
            enabled = isScanning && !isProcessing,
            colors = ButtonDefaults.buttonColors(containerColor = if (isCapturing) AccentRed else AccentGreen),
            shape = RoundedCornerShape(8.dp)
        ) { Text(if (isCapturing) "停止缓存" else "开始缓存") }
        Button(
            onClick = onShowCache,
            modifier = Modifier.weight(1f),
            enabled = processingState is CaptureProcessingState.Idle && !isCapturing,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(8.dp)
        ) { Text("选择缓存") }
    }
}

@Composable
private fun CachePickerDialog(
    items: List<CaptureCacheRepository.CaptureCacheItem>,
    onDismiss: () -> Unit,
    onSelect: (CaptureCacheRepository.CaptureCacheItem) -> Unit,
    onDelete: (CaptureCacheRepository.CaptureCacheItem) -> Unit,
    onDeleteAll: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        shape = RoundedCornerShape(8.dp),
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择缓存", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    IconButton(onClick = onDeleteAll) {
                        Icon(Icons.Default.Delete, contentDescription = "删除全部", tint = AccentRed)
                    }
                }
            }
        },
        text = {
            Column {
                if (items.isEmpty()) {
                    Text("暂无缓存", color = TextSecondary)
                } else {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text("${formatDuration(item.durationMs)}  ${formatSize(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "移除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ProcessingOverlay(state: CaptureProcessingState, viewModel: MainViewModel) {
    val show = state
    if (show !is CaptureProcessingState.Processing && show !is CaptureProcessingState.Capturing) return
    val isProcessing = show is CaptureProcessingState.Processing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (isProcessing) "正在解析缓存" else "正在保存缓存", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (isProcessing) {
                val p = show as CaptureProcessingState.Processing
                Text(p.phase, color = TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("${(p.progress * 100).toInt()}%  帧 ${p.processedFrames}  关键 ${p.keyFrames}", style = MaterialTheme.typography.bodyMedium)
                Text("识别 ${p.identifiedCount}  新增 ${p.addedCount}", style = MaterialTheme.typography.bodyMedium)
                p.etaMs?.let { eta ->
                    Spacer(Modifier.height(6.dp))
                    Text("预计剩余 ${formatDuration(eta)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.cancelProcessing() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("停止解析") }
            } else {
                Text("正在写入缓存文件…", color = TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("保存阶段无法中断", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
}

private fun formatSize(sizeBytes: Long): String {
    val mb = sizeBytes / (1024f * 1024f)
    return "%.1f MB".format(mb)
}
