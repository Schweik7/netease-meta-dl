package com.schweik.nmdl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schweik.nmdl.MainViewModel
import com.schweik.nmdl.core.Downloader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val opts by vm.options.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    if (showPicker) {
        DirPickerDialog(
            start = opts.directory,
            onDismiss = { showPicker = false },
            onPick = { path ->
                vm.update { it.copy(directory = path) }
                showPicker = false
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("nmdl · 歌词封面下载") }) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            // ---- 控制区（内容多时自己滚）----
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (!hasStoragePermission) {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "需要「所有文件访问权限」",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "歌词和封面要写在歌曲文件旁边，这在 Android 11+ 上只能靠这个权限。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onRequestPermission) { Text("去授权") }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = opts.directory,
                    onValueChange = { vm.update { o -> o.copy(directory = it) } },
                    label = { Text("音乐目录") },
                    singleLine = true,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = { showPicker = true }) { Text("选择") }
                    },
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = opts.lyric,
                        onClick = { vm.update { o -> o.copy(lyric = !o.lyric) } },
                        label = { Text("歌词") },
                    )
                    FilterChip(
                        selected = opts.cover,
                        onClick = { vm.update { o -> o.copy(cover = !o.cover) } },
                        label = { Text("封面") },
                    )
                    FilterChip(
                        selected = opts.translation,
                        onClick = { vm.update { o -> o.copy(translation = !o.translation) } },
                        label = { Text("含翻译") },
                    )
                    FilterChip(
                        selected = opts.recursive,
                        onClick = { vm.update { o -> o.copy(recursive = !o.recursive) } },
                        label = { Text("含子目录") },
                    )
                }

                SwitchRow("覆盖已有的歌词/封面", opts.force) {
                    vm.update { o -> o.copy(force = it) }
                }
                SwitchRow("只重试上次没成功的", opts.retryFailed) {
                    vm.update { o -> o.copy(retryFailed = it) }
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "▴ 高级选项" else "▾ 高级选项")
                }

                if (showAdvanced) {
                    NumberField("并发数", opts.workers) { vm.update { o -> o.copy(workers = it) } }
                    DecimalField("限速（请求/秒）", opts.rps) {
                        vm.update { o -> o.copy(rps = it) }
                    }
                    NumberField("封面边长（像素）", opts.coverSize) {
                        vm.update { o -> o.copy(coverSize = it) }
                    }
                    DecimalField("最低匹配分（满分约 138）", opts.minScore) {
                        vm.update { o -> o.copy(minScore = it) }
                    }
                    NumberField("每次搜索候选数", opts.searchLimit) {
                        vm.update { o -> o.copy(searchLimit = it) }
                    }
                    NumberField("只处理前 N 首（0 = 全部）", opts.limit) {
                        vm.update { o -> o.copy(limit = it) }
                    }
                    OutlinedTextField(
                        value = opts.only,
                        onValueChange = { vm.update { o -> o.copy(only = it) } },
                        label = { Text("只处理文件名含这段文字的") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    SwitchRow("歌词带 UTF-8 BOM（兼容性更好）", opts.lyricBom) {
                        vm.update { o -> o.copy(lyricBom = it) }
                    }
                    SwitchRow("试运行（只匹配，不写文件）", opts.dryRun) {
                        vm.update { o -> o.copy(dryRun = it) }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { if (running) vm.stop() else vm.start() },
                        enabled = hasStoragePermission && opts.directory.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (running) "停止" else "开始") }
                    OutlinedButton(
                        onClick = { vm.scan() },
                        enabled = !running && hasStoragePermission,
                    ) { Text("扫描") }
                    OutlinedButton(onClick = { vm.clearLog() }, enabled = !running) {
                        Text("清空")
                    }
                }

                val (done, total) = progress
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$done / $total",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }

            HorizontalDivider()

            // ---- 日志区 ----
            val listState = rememberLazyListState()
            LaunchedEffect(log.size) {
                if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                itemsIndexed(log) { _, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun DecimalField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

/** 预览用的空壳，方便在 IDE 里看布局。 */
@Suppress("unused")
private fun previewOptions() = Downloader.Options(directory = "/storage/emulated/0/Music")
