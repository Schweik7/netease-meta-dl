package com.schweik.nmdl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schweik.nmdl.core.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 界面状态与任务调度。
 *
 * 日志行数封顶 [MAX_LOG]：跑几百首会刷出上千行，全留着会把内存和
 * LazyColumn 都拖垮，超了就从头丢。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val MAX_LOG = 800
    }

    private val _options = MutableStateFlow(Prefs.load(app))
    val options: StateFlow<Downloader.Options> = _options.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 进度：已完成 / 总数；总数为 0 表示还没开始。 */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private var job: Job? = null

    fun update(block: (Downloader.Options) -> Downloader.Options) {
        _options.value = block(_options.value)
        Prefs.save(getApplication(), _options.value)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /** 会被多个下载协程同时调用，必须用 update 做原子的读-改-写。 */
    private fun appendLog(line: String) = _log.update { cur ->
        if (cur.size >= MAX_LOG) cur.drop(cur.size - MAX_LOG + 1) + line else cur + line
    }

    /** 快速看一眼目录里有多少首歌、已经配好了多少，不发任何网络请求。 */
    fun scan() {
        if (_running.value) return
        val opts = _options.value
        viewModelScope.launch {
            val root = File(opts.directory)
            if (opts.directory.isEmpty() || !root.isDirectory) {
                appendLog("请先选择一个存在的音乐目录。")
                return@launch
            }
            appendLog("正在扫描 ${root.absolutePath} …")
            val result = withContext(Dispatchers.IO) {
                val files = com.schweik.nmdl.core.Scanner.collect(root, opts.recursive, opts.only)
                var withLrc = 0
                var withJpg = 0
                for (f in files) {
                    val stem = f.absolutePath.substringBeforeLast('.')
                    if (File("$stem.lrc").exists()) withLrc++
                    if (File("$stem.jpg").exists()) withJpg++
                }
                Triple(files.size, withLrc, withJpg)
            }
            val (total, withLrc, withJpg) = result
            appendLog("共 $total 首音乐：已有歌词 $withLrc 个，已有封面 $withJpg 个")
            if (total > 0) {
                appendLog("还缺歌词 ${total - withLrc} 个，缺封面 ${total - withJpg} 个")
            }
        }
    }

    fun start() {
        if (_running.value) return
        val opts = _options.value
        if (opts.directory.isEmpty()) {
            appendLog("请先选择音乐目录。")
            return
        }
        _running.value = true
        _progress.value = 0 to 0
        job = viewModelScope.launch {
            try {
                Downloader.run(
                    getApplication(), opts,
                    Downloader.Sink(
                        log = ::appendLog,
                        progress = { done, total -> _progress.value = done to total },
                    ),
                )
            } catch (_: CancellationException) {
                appendLog("已停止。")
            } catch (e: Exception) {
                appendLog("出错：${e.message}")
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }
}
