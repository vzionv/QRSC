package com.example.qrsc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val isScanning by viewModel.isScanning.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val scanIntervalMs by viewModel.scanIntervalMs.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val isPreviewMode by viewModel.isPreviewMode.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    val lockedBitmap by viewModel.lockedBitmap.collectAsState()
    val countdownSetting by viewModel.countdownSetting.collectAsState()
    val countdownCurrent by viewModel.countdownCurrent.collectAsState()
    val isCountingDown by viewModel.isCountingDown.collectAsState()

    // 黑屏模式是纯 UI 状态，不涉及 Service
    var isBlackScreenMode by remember { mutableStateOf(false) }

    // 通知权限请求（Android 13+）
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

    // 扫描间隔 Slider: 0f = 100ms, 1f = 5000ms
    var sliderPosition by remember {
        mutableFloatStateOf((scanIntervalMs - 100f) / 4900f)
    }

    // 黑屏模式：全黑覆盖层 + 长按 2 秒退出
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
                                if (event.changes.all { !it.pressed }) {
                                    return@withTimeoutOrNull false
                                }
                            }
                        }
                        if (timedOut == null) {
                            isBlackScreenMode = false
                        }
                    }
                }
        )
        return
    }

    // 正常界面
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "二维码剪贴板助手",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 预览模式切换按钮
        Button(
            onClick = { viewModel.togglePreviewMode() },
            modifier = Modifier.fillMaxWidth(),
            enabled = isScanning,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isPreviewMode) "显示文本" else "查看预览",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 内容区域：根据预览模式切换
        if (isPreviewMode) {
            // === 预览区 ===
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface),
                contentAlignment = Alignment.Center
            ) {
                val displayBitmap = lockedBitmap ?: previewBitmap
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "摄像头预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "等待画面…",
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                // 倒计时叠加层
                if (isCountingDown) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${countdownCurrent}",
                            style = MaterialTheme.typography.displayLarge,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 倒计时控件 / 解锁按钮
            if (isCountingDown) {
                Button(
                    onClick = { viewModel.cancelCountdown() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("取消拍摄")
                }
            } else if (lockedBitmap != null) {
                Button(
                    onClick = { viewModel.unlockPreview() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("解锁预览")
                }
            } else {
                // 倒计时设置行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "延迟拍摄:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Button(
                        onClick = { viewModel.setCountdownSetting(countdownSetting - 1) },
                        enabled = countdownSetting > 1,
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("−", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        text = "${countdownSetting}秒",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = { viewModel.setCountdownSetting(countdownSetting + 1) },
                        enabled = countdownSetting < 10,
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = { viewModel.startCountdown() },
                        enabled = previewBitmap != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("拍摄", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            // === 文本显示区 ===
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
            ) {
                val textScrollState = rememberScrollState()
                Text(
                    text = currentText.ifEmpty { "等待扫描…" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(textScrollState)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    ),
                    color = if (currentText.isEmpty()) TextSecondary
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = if (currentText.isEmpty()) TextAlign.Center else TextAlign.Start,
                    minLines = 8
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isScanning) "● 扫描中" else "○ 已停止",
            color = if (isScanning) AccentGreen else TextSecondary,
            style = MaterialTheme.typography.labelLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 开始/停止扫描
        Button(
            onClick = {
                if (isScanning) {
                    viewModel.stopScanning()
                    context.stopService(Intent(context, ScannerForegroundService::class.java))
                } else {
                    if (!cameraPermissionState.status.isGranted) {
                        cameraPermissionState.launchPermissionRequest()
                        return@Button
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
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
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isScanning) AccentRed else AccentGreen
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isScanning) "停止扫描" else "开始扫描",
                style = MaterialTheme.typography.titleMedium
            )
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
            enabled = isScanning,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isFrontCamera) "切换至后置摄像头" else "切换至前置摄像头",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "当前扫描间隔：${"%.1f".format(scanIntervalMs / 1000f)} 秒",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Slider(
            value = sliderPosition,
            onValueChange = { newPos ->
                sliderPosition = newPos
                val newIntervalMs = (100f + newPos * 4900f).toLong()
                viewModel.setScanInterval(newIntervalMs.toFloat())
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent
            ),
            valueRange = 0f..1f,
            steps = 0
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "0.1 秒", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(text = "5.0 秒", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { isBlackScreenMode = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "进入黑屏扫描模式",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "黑屏模式下长按 2 秒恢复界面",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
    }
}
