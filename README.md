# nmdl — 网易云歌词/封面批量下载

两个独立的功能，命令行和图形界面两个入口：

- **`nmdl download`** —— 扫描目录里的音乐文件，按「歌手 - 歌名」去网易云匹配，把**歌词**
  存成同名 `.lrc`、**封面**存成同名 `.jpg`；
- **`nmdl convert`** —— 把 **FLAC 转成 MP3**，喂给不认 FLAC 的播放设备。

匹配逻辑参考 [zhongyang219/MusicPlayer2](https://github.com/zhongyang219/MusicPlayer2)
的歌词下载模块（`NeteaseLyricDownload.cpp` / `LyricDownloadCommon.cpp`），接口地址一致，
打分权重也照搬它的 `SelectMatchedItem`，另外补上了它注释里列了权值、但代码中没实现的
**时长判定**——本地文件的时长是最硬的证据，能有效排掉同名的现场版、翻唱和重制版。

## 用法

```bash
# 图形界面（推荐）：左侧边栏选功能
uv run nmdl-gui

# 命令行
uv run nmdl download --dir "D:\Music\Music"   # 处理该目录，已有 .lrc/.jpg 的跳过
uv run nmdl download --limit 10 --dry-run     # 先拿 10 首试，只看匹配结果不写文件
uv run nmdl download --retry-failed           # 只重试上次没成功的
uv run nmdl download --only "Adele"           # 只处理文件名含 Adele 的
uv run nmdl download --force                  # 已有的也重下

uv run nmdl convert --dir "D:\Music\Music"    # 把该目录的 FLAC 转成 MP3

uv run nmdl --help                            # 全部参数
```

子命令可以省略，`nmdl --dir ...` 等同于 `nmdl download --dir ...`。

首次使用：

```bash
uv sync --extra gui     # 带 GUI；只要命令行的话 uv sync 就够
```

## 产出

| 文件 | 说明 |
| --- | --- |
| `<同名>.lrc` | 歌词，UTF-8 with BOM；有中文翻译时按**同一时间戳的下一行**写入，MusicPlayer2 和多数播放器都认这种格式 |
| `<同名>.jpg` | 专辑封面，默认 800×800 |
| `.nmdl_cache.json` | 每首歌的匹配结果（歌曲 ID、得分、状态），重跑时用来跳过已完成的 |
| `nmdl_report.txt` | 没匹配上或有问题的条目清单，方便人工核对 |

默认**不改动音频文件本身**。想把封面和标签写进文件里再加 `--embed-cover` / `--write-tags`。

## FLAC 转 MP3 —— `nmdl convert`

设备不认 FLAC 的话用这个。它跟歌词封面下载**完全独立**，只管转格式、不碰网络。

```bash
uv run nmdl convert --dir "D:\Music"                       # 原地转成 320k MP3
uv run nmdl convert -r --bitrate V0 --output "D:\ToPhone"   # 递归 + VBR V0，另存到别的目录
uv run nmdl convert --dry-run                              # 只列出要转哪些，不动文件
```

| 参数 | 说明 |
| --- | --- |
| `--dir` `-r` `--only` `--limit` | 扫哪些文件，跟 `download` 一个意思 |
| `--bitrate` | `320k`(默认) `256k` `192k` `128k` / `V0` `V2`（VBR，V0 体积小一半、听感接近 320k） |
| `--output` | 输出目录，留空则和源文件放一起；配 `-r` 时会复刻子目录结构 |
| `--workers` | 并发数，0（默认）按 CPU 核数自动决定 |
| `--delete-source` | ⚠ 转换成功后删掉源 FLAC，不可撤销 |
| `--ffmpeg` | ffmpeg 路径，留空从 PATH 找 |
| `--force` | 目标 mp3 已存在也重新转 |

原有的**标签和内嵌封面会一起转过去**（写成 ID3v2.3，兼容性最好）；个别 FLAC 的内嵌图片
塞不进 ID3 时会自动丢掉图片重转一次——这种情况事后跑一次 `nmdl download` 补封面就行。
目标 mp3 已存在就跳过，`--force` 才重转。转换先写 `.part` 临时文件再改名，中途中断不会
留下半成品。

想给转出来的 MP3 配歌词封面，转完再跑一次 `nmdl download` 即可。

需要 **ffmpeg**：

```bash
winget install Gyan.FFmpeg     # Windows；装完重开终端
```

## 关于限流

网易云的公开接口按 IP 限流，触发后返回 HTTP 200、但 body 是
`{"code":405,"msg":"操作频繁，请稍候再试"}`，而且**封禁期间继续请求会不断续期**。

实测下来：

- 并发 4 线程不限速 → 约 30 个请求后被封，之后连续 200 秒轮询都不解封；
- **1 请求/秒 → 45/45 全部成功**，所以默认 `--rps 1.0`。

程序里对应做了三件事：全局令牌桶（所有线程共用一个间隔）、撞限流时**所有线程一起**
停下来冷却（冷却时间逐次翻倍）、以及缓存断点续传——中断后重跑会跳过已完成的歌。

一首歌固定 2 个接口请求（搜索走 `cloudsearch`，返回里直接带封面地址，省掉一次
详情请求；封面图走 CDN，不占限流额度）。500 首大约 20 分钟。

### 换 IP（可选）

不想等冷却的话，可以让它撞限流时通过 Clash 换节点：

```bash
uv run nmdl download --rotate-ip --proxy http://127.0.0.1:7890
```

会自动探测本机 Clash 里负责 `music.163.com` 流量的策略组（通常叫 `NetEaseMusic`），
撞限流时换下一个节点，连不上 163 的节点自动拉黑跳过。

⚠️ 策略组是系统级的，切换会影响本机其它网易云流量。程序**退出时一定会还原**原来的选择
（正常结束、异常、Ctrl-C 都会还原）。另外部分海外节点会被 163 直接重置连接，属正常现象。

## 匹配不上怎么办

看 `nmdl_report.txt`。常见原因和处理：

- 文件名不是「歌手 - 歌名」格式 → 改好文件名后 `download --retry-failed`；
- 网易云上确实没有这首（小众曲、自制曲）；
- 纯音乐 → 报告里标 `纯音乐/无歌词`，属正常，重跑不会再去请求；
- 匹配到了但明显不对 → 调高 `--min-score`（默认 45，满分约 138）。

## 安卓版

`android/` 下是同一套功能的安卓 app（Kotlin + Compose），只做「下载歌词封面」这一半。
匹配打分、限流退避、`.nmdl_cache.json` 的格式都和桌面版一致——同一个音乐文件夹在
电脑和手机上轮流跑，进度是互通的。

构建和使用说明见 [android/README.md](android/README.md)。

## 代码结构

```
src/nmdl/                        桌面版（Python）
  net.py       全局限流、反限流退避、代理
  netease.py   搜索 / 歌词 / 封面接口封装
  matcher.py   文件名解析 + 匹配打分（MusicPlayer2 的权重）
  lrc.py       LRC 解析与原文+翻译合并
  tagging.py   读时长；可选地写封面/标签进音频
  convert.py   convert 子命令：FLAC -> MP3（调 ffmpeg，保留标签和内嵌封面）
  core.py      download 子命令：批量调度、缓存、报告
  cli.py       参数定义（CLI 与 GUI 共用同一个 parser）
  gui.py       Gooey 界面，直接渲染 cli.py 的 parser

android/app/src/main/java/com/schweik/nmdl/    安卓版（Kotlin）
  core/Http.kt        限流 + 退避 + 重试，对应 net.py
  core/Netease.kt     接口封装，对应 netease.py
  core/Matcher.kt     匹配打分，对应 matcher.py（含 difflib 相似度的等价实现）
  core/Lrc.kt         歌词合并，对应 lrc.py
  core/Scanner.kt     扫目录 + 读时长，对应 core.collect / tagging.duration_of
  core/Downloader.kt  批量调度、缓存、报告，对应 core.py
  ui/                 Compose 界面
```

CLI 和 GUI 共用 `cli.build_parser()`，加参数两边同时就有了；两个子命令在 GUI 里就是
左侧边栏的两页。
