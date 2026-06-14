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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.qrsc.ui.theme.Accent
import com.example.qrsc.ui.theme.AccentRed
import com.example.qrsc.ui.theme.TextSecondary
import java.io.File

@Composable
fun SettingsTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scanIntervalMs by viewModel.scanIntervalMs.collectAsState()
    var sliderPosition by remember { mutableFloatStateOf((scanIntervalMs - 100f) / 4900f) }

    val savePath = context.getExternalFilesDir(null)?.absolutePath + "/QRFileTransfer"

    // SAF export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)

        val tree = DocumentFile.fromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val decodedDir = FileUtils.decodedDir(context)
        val files = decodedDir.listFiles { f -> f.isFile } ?: arrayOf()
        var copied = 0
        for (f in files) {
            try {
                val docFile = tree.createFile("*/*", f.name) ?: continue
                context.contentResolver.openOutputStream(docFile.uri)?.use { out ->
                    out.write(f.readBytes())
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
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text("设置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(24.dp))

        // 扫描间隔
        Text("扫描间隔", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text("${"%.1f".format(scanIntervalMs / 1000f)} 秒",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
        Slider(value = sliderPosition, onValueChange = { p ->
            sliderPosition = p; viewModel.setScanInterval((100f + p * 4900f).toLong().toFloat())
        }, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            valueRange = 0f..1f, steps = 0)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("0.1 秒", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("5.0 秒", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        Spacer(Modifier.height(32.dp))

        // 保存目录
        Text("保存目录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(savePath, style = MaterialTheme.typography.bodySmall, color = TextSecondary,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))

        // 导出到… 按钮
        Button(onClick = { exportLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text("导出文件") }

        Spacer(Modifier.height(32.dp))

        // 缓存管理
        Text("缓存管理", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val count = FilePacketState.clearAll()
            Toast.makeText(context, "已清除 $count 个组", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) { Text("清除所有缓存") }

        Spacer(Modifier.height(32.dp))
    }
}
