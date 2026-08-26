# nmdl — 网易云歌词/封面批量下载

扫描目录里的音乐文件，按「歌手 - 歌名」去网易云匹配，把**歌词**存成同名 `.lrc`、
**封面**存成同名 `.jpg`。同一套参数有命令行和图形界面两个入口。

匹配逻辑参考 [zhongyang219/MusicPlayer2](https://github.com/zhongyang219/MusicPlayer2)
的歌词下载模块（`NeteaseLyricDownload.cpp` / `LyricDownloadCommon.cpp`），接口地址一致，
打分权重也照搬它的 `SelectMatchedItem`，另外补上了它注释里列了权值、但代码中没实现的
**时长判定**——本地文件的时长是最硬的证据，能有效排掉同名的现场版、翻唱和重制版。

## 用法

```bash
# 图形界面（推荐）
uv run nmdl-gui

# 命令行
uv run nmdl --dir "D:\Music\Music"      # 处理该目录，已有 .lrc/.jpg 的跳过
uv run nmdl --limit 10 --dry-run        # 先拿 10 首试，只看匹配结果不写文件
uv run nmdl --retry-failed              # 只重试上次没成功的
uv run nmdl --only "Adele"              # 只处理文件名含 Adele 的
uv run nmdl --force                     # 已有的也重下
uv run nmdl --help                      # 全部参数
```

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
uv run nmdl --rotate-ip --proxy http://127.0.0.1:7890
```

会自动探测本机 Clash 里负责 `music.163.com` 流量的策略组（通常叫 `NetEaseMusic`），
撞限流时换下一个节点，连不上 163 的节点自动拉黑跳过。

⚠️ 策略组是系统级的，切换会影响本机其它网易云流量。程序**退出时一定会还原**原来的选择
（正常结束、异常、Ctrl-C 都会还原）。另外部分海外节点会被 163 直接重置连接，属正常现象。

## 匹配不上怎么办

看 `nmdl_report.txt`。常见原因和处理：

- 文件名不是「歌手 - 歌名」格式 → 改好文件名后 `--retry-failed`；
- 网易云上确实没有这首（小众曲、自制曲）；
- 纯音乐 → 报告里标 `纯音乐/无歌词`，属正常，重跑不会再去请求；
- 匹配到了但明显不对 → 调高 `--min-score`（默认 45，满分约 138）。

## 代码结构

```
src/nmdl/
  net.py       全局限流、反限流退避、代理
  netease.py   搜索 / 歌词 / 封面接口封装
  matcher.py   文件名解析 + 匹配打分（MusicPlayer2 的权重）
  lrc.py       LRC 解析与原文+翻译合并
  tagging.py   读时长；可选地写封面/标签进音频
  core.py      批量调度、缓存、报告
  cli.py       参数定义（CLI 与 GUI 共用同一个 parser）
  gui.py       Gooey 界面，直接渲染 cli.py 的 parser
```

CLI 和 GUI 共用 `cli.build_parser()`，加参数两边同时就有了。
