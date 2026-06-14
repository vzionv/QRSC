package com.example.qrsc

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.qrsc.ui.theme.Accent
import com.example.qrsc.ui.theme.AccentRed
import com.example.qrsc.ui.theme.TextSecondary

@Composable
fun SettingsTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scanIntervalMs by viewModel.scanIntervalMs.collectAsState()
    val keyFrameThreshold by viewModel.keyFrameThreshold.collectAsState()
    val captureFps by viewModel.captureFps.collectAsState()
    val autoProcess by viewModel.autoProcess.collectAsState()
    val isAutoThreshold by viewModel.isAutoThreshold.collectAsState()
    var sliderPosition by remember { mutableFloatStateOf((scanIntervalMs - 100f) / 4900f) }
    var thresholdPosition by remember { mutableFloatStateOf(keyFrameThreshold) }
    var fpsPosition by remember { mutableFloatStateOf((captureFps - 1f) / 29f) }

    val savePath = FileUtils.baseDir(context).absolutePath

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some document providers grant one-shot access only; exporting can still proceed.
        }
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val decodedDir = FileUtils.decodedDir(context)
        val files = decodedDir.listFiles { f -> f.isFile } ?: arrayOf()
        var copied = 0
        for (f in files) {
            try {
                val docFile = tree.createFile("*/*", f.name) ?: continue
                context.contentResolver.openOutputStream(docFile.uri)?.use { out ->
                    f.inputStream().use { input -> input.copyTo(out) }
                }
                copied++
            } catch (_: Exception) { }
        }
        Toast.makeText(context, "已导出 $copied/${files.size} 个文件", Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 采集设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("采集参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))

                // 扫描间隔
                SettingSliderRow("扫描间隔", "${"%.1f".format(scanIntervalMs / 1000f)}秒",
                    sliderPosition, { sliderPosition = it; viewModel.setScanInterval((100f + it * 4900f).toLong().toFloat()) })
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(Modifier.height(6.dp))

                // 缓存帧率
                SettingSliderRow("缓存帧率", "${captureFps}fps",
                    fpsPosition, { fpsPosition = it; viewModel.setCaptureFps((1 + it * 29).toInt()) })
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(Modifier.height(6.dp))

                // 关键帧阈值
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("关键帧阈值", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onBackground)
                    if (!isAutoThreshold) {
                        Slider(value = thresholdPosition, onValueChange = { thresholdPosition = it; viewModel.setKeyFrameThreshold(it) },
                            modifier = Modifier.weight(1f).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                            valueRange = 0f..1f, steps = 0)
                        Text("${"%.2f".format(keyFrameThreshold)}", style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.width(40.dp))
                    } else {
                        Text("自动", style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.weight(1f))
                    }
                    Button(
                        onClick = { viewModel.setAutoThreshold(!isAutoThreshold) },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isAutoThreshold) Accent else MaterialTheme.colorScheme.surfaceVariant),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(if (isAutoThreshold) "自动" else "手动", style = MaterialTheme.typography.labelSmall,
                            color = if (isAutoThreshold) MaterialTheme.colorScheme.onPrimary else TextSecondary)
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(Modifier.height(6.dp))

            }
        }

        Spacer(Modifier.height(8.dp))

        // 处理设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("处理设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("停止缓存后自动解析", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoProcess,
                        onCheckedChange = { viewModel.setAutoProcess(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 存储
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("存储", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(savePath, style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportLauncher.launch(null) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("导出文件") }
                    Button(onClick = {
                        val count = FilePacketState.clearAll()
                        Toast.makeText(context, "已清除 $count 个组", Toast.LENGTH_SHORT).show()
                    }, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) { Text("清除缓存") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onBackground)
        Slider(value = sliderValue, onValueChange = onSliderChange,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            valueRange = 0f..1f, steps = 0)
        Text(valueText, style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.width(56.dp))
    }
}
