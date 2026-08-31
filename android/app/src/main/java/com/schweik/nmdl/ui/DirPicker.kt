package com.schweik.nmdl.ui

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 一个够用的目录浏览对话框。
 *
 * app 已经有「所有文件访问权限」，直接用 [File] 浏览就行——比 SAF 的
 * DocumentFile 快得多，选出来的也是真实路径，能直接交给下载流程。
 */
@Composable
fun DirPickerDialog(
    start: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var current by remember {
        mutableStateOf(
            File(start).takeIf { start.isNotEmpty() && it.isDirectory } ?: root
        )
    }
    val dirs by remember(current) {
        mutableStateOf(
            current.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音乐目录") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { current.parentFile?.let { current = it } },
                        enabled = current.parentFile != null &&
                            current.absolutePath != "/",
                    ) { Text("↑ 上一级") }
                    Text(
                        current.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LazyColumn(Modifier.height(320.dp)) {
                    items(dirs) { d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { current = d }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📁", Modifier.padding(end = 12.dp))
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (dirs.isEmpty()) {
                        item {
                            Text(
                                "（没有子目录）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current.absolutePath) }) { Text("选择这个目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
