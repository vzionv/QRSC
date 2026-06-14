package com.example.qrsc

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qrsc.ui.theme.Accent
import com.example.qrsc.ui.theme.AccentGreen
import com.example.qrsc.ui.theme.AccentRed
import com.example.qrsc.ui.theme.Surface
import com.example.qrsc.ui.theme.SurfaceVariant
import com.example.qrsc.ui.theme.TextSecondary

@Composable
fun FilesTab(@Suppress("UNUSED_PARAMETER") viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val groups by FilePacketState.chunkGroups.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("接收的文件", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无文件\n扫描文件二维码后自动显示在此处", color = TextSecondary, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.transmissionId }) { group ->
                    FileGroupCard(group = group, onDecode = {
                        val result = FileUtils.assembleFile(group, context)
                        if (result != null) {
                            Toast.makeText(context, "${result.name} 已保存", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "解码失败: 子包不完整", Toast.LENGTH_SHORT).show()
                        }
                    }, onDelete = { FilePacketState.removeGroup(group.transmissionId) })
                }
            }
        }
    }
}

@Composable
private fun FileGroupCard(
    group: FilePacketState.ChunkGroup,
    onDecode: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val complete = group.isComplete

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.filename, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (complete) "已完成 (${group.totalChunks} 块)"
                        else "已接收 ${group.receivedCount} / ${group.totalChunks} 块",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (complete) AccentGreen else AccentRed
                    )
                    if (!complete && group.firstMissing >= 0) {
                        Text(
                            "缺 #${group.firstMissing}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentRed
                        )
                    }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开 (${group.receivedCount})")
                }
            }

            // Expandable chunk list
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = SurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))

                    group.chunks.sortedBy { it.index }.forEach { chunk ->
                        ChunkRow(chunk = chunk, groupId = group.transmissionId)
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDecode, enabled = complete, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        disabledContainerColor = SurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("解码") }
                Button(onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ChunkRow(chunk: FilePacketState.ChunkInfo, groupId: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#${chunk.index}",
            style = MaterialTheme.typography.bodyMedium,
            color = Accent,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = "${chunk.payload.size} 字节",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            FilePacketState.removeChunk(groupId, chunk.index)
        }) {
            Text("删除", color = AccentRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}
