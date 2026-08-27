"""命令行入口。

两个互相独立的功能，各自一个子命令：

* ``nmdl download`` —— 给本地音乐下载网易云的歌词和封面
* ``nmdl convert``  —— 把目录里的 FLAC 转成 MP3

参数定义由 CLI 和 GUI 共用（GUI 只是把同一个 parser 渲染成窗口）。
不写子命令时默认当成 ``download``，老的 ``nmdl --dir ...`` 写法继续能用。
"""

from __future__ import annotations

import argparse
import sys

from . import convert
from .core import Options, run

DESCRIPTION = "本地音乐的网易云歌词/封面下载 + FLAC 转 MP3"

DOWNLOAD_DESC = "扫描目录里的音乐，按「歌手 - 歌名」匹配网易云，下载歌词(.lrc)和封面(.jpg)"
DOWNLOAD_EPILOG = """\
示例:
  nmdl download                            处理当前目录，跳过已有歌词/封面的
  nmdl download --dir D:\\Music --limit 10   先拿 10 首试试
  nmdl download --dry-run                  只看匹配结果，不写任何文件
  nmdl download --retry-failed             只重试上次没成功的
  nmdl download --rotate-ip                撞限流时用 Clash 换节点换 IP 继续
"""

CONVERT_DESC = "把目录里的 FLAC 转成 MP3（需要 ffmpeg），标签和内嵌封面原样保留"
CONVERT_EPILOG = """\
示例:
  nmdl convert --dir D:\\Music              原地把 FLAC 转成 320k 的 MP3
  nmdl convert -r --output D:\\MP3          递归转换，结果另存到别的目录
  nmdl convert --bitrate V0                用 VBR，体积更小、听感接近 320k
  nmdl convert --dry-run                   只列出要转哪些，不动文件

转完想给 MP3 配歌词封面，再跑一次 nmdl download 即可。
"""

# GUI 侧边栏上显示的名字（CLI 下保持默认 prog，help 里才不会出现中文 usage）
GUI_TITLES = {"download": "下载歌词封面", "convert": "FLAC 转 MP3"}

COMMANDS = ("download", "convert")


def _add_download_args(p, w) -> None:
    g = p.add_argument_group("基本")
    g.add_argument("--dir", dest="directory", default=".",
                   help="音乐所在目录", **w("DirChooser"))
    g.add_argument("-r", "--recursive", action="store_true", help="连子目录一起处理")
    g.add_argument("--only", default="", help="只处理文件名里含这段文字的歌曲")
    g.add_argument("--limit", type=int, default=0, help="只处理前 N 首，0 表示全部")
    g.add_argument("--force", action="store_true", help="已有 .lrc/.jpg 也重新下载")
    g.add_argument("--retry-failed", action="store_true", help="只重试上次没成功的歌曲")
    g.add_argument("--dry-run", action="store_true", help="只匹配、不写文件")

    g = p.add_argument_group("下载内容")
    g.add_argument("--no-lyric", action="store_true", help="不下载歌词")
    g.add_argument("--no-cover", action="store_true", help="不下载封面")
    g.add_argument("--no-translation", action="store_true", help="歌词不合并中文翻译")
    g.add_argument("--cover-size", type=int, default=800, help="封面边长像素")
    g.add_argument("--encoding", default="utf-8-sig",
                   choices=["utf-8-sig", "utf-8", "gbk"],
                   help="lrc 文件编码，默认 utf-8-sig（带 BOM，播放器兼容性最好）",
                   **w("Dropdown"))
    g.add_argument("--embed-cover", action="store_true",
                   help="同时把封面写进音频文件（会改动音频文件）")
    g.add_argument("--write-tags", action="store_true",
                   help="同时把标题/歌手/专辑写进音频标签（会改动音频文件）")

    g = p.add_argument_group("匹配与限速")
    g.add_argument("--min-score", type=float, default=45.0,
                   help="最低匹配分，满分约 138；调高更严格")
    g.add_argument("--search-limit", type=int, default=15, help="每次搜索取多少个候选")
    g.add_argument("--workers", type=int, default=3, help="并发线程数")
    g.add_argument("--rps", type=float, default=1.0,
                   help="每秒请求数上限（实测 1.0 稳定，调高容易被限流）")
    g.add_argument("--cooldown", type=float, default=60.0,
                   help="撞限流后的基础冷却秒数，会逐次翻倍")

    g = p.add_argument_group("代理 / 换 IP")
    g.add_argument("--proxy", default="", help="HTTP 代理，例如 http://127.0.0.1:7890")
    g.add_argument("--rotate-ip", action="store_true",
                   help="撞限流时通过 Clash 换节点换 IP（需要同时设置 --proxy）")
    g.add_argument("--clash-api", default="http://127.0.0.1:28845",
                   help="Clash 外部控制接口地址")
    g.add_argument("--clash-group", default="",
                   help="要切换的策略组名，留空则自动探测网易云走的那个组")
    g.add_argument("--clash-secret", default="", help="Clash 控制接口密码（如果有）")


def _add_convert_args(p, w) -> None:
    g = p.add_argument_group("基本")
    g.add_argument("--dir", dest="directory", default=".",
                   help="FLAC 所在目录", **w("DirChooser"))
    g.add_argument("-r", "--recursive", action="store_true", help="连子目录一起处理")
    g.add_argument("--only", default="", help="只转文件名里含这段文字的歌曲")
    g.add_argument("--limit", type=int, default=0, help="只转前 N 个，0 表示全部")
    g.add_argument("--force", action="store_true", help="目标 mp3 已存在也重新转")
    g.add_argument("--dry-run", action="store_true", help="只列出要转哪些，不动文件")

    g = p.add_argument_group("转换选项")
    g.add_argument("--bitrate", default="320k",
                   choices=["320k", "256k", "192k", "128k", "V0", "V2"],
                   help="MP3 码率；V0/V2 是 VBR，V0 体积更小、听感接近 320k",
                   **w("Dropdown"))
    g.add_argument("--output", default="",
                   help="输出目录，留空则和源文件放一起（递归时会复刻子目录结构）",
                   **w("DirChooser"))
    g.add_argument("--workers", type=int, default=0,
                   help="并发数，0 表示按 CPU 核数自动决定")
    g.add_argument("--delete-source", action="store_true",
                   help="⚠ 转换成功后删除源 FLAC 文件（不可撤销）")
    g.add_argument("--ffmpeg", default="",
                   help="ffmpeg 可执行文件路径，留空则从 PATH 里找",
                   **w("FileChooser"))


def build_parser(parser_class=argparse.ArgumentParser, gui: bool = False):
    """构造参数解析器。gui=True 时给控件加上 Gooey 的 widget 提示。"""

    def w(name: str, **extra):
        """只在 GUI 模式下附加 widget 相关参数。"""
        return dict(widget=name, **extra) if gui else {}

    def title(cmd: str):
        # Gooey 拿 prog 当侧边栏标题；CLI 下不改 prog，免得 --help 里冒出中文 usage
        return {"prog": GUI_TITLES[cmd]} if gui else {}

    p = parser_class(description=DESCRIPTION,
                     formatter_class=argparse.RawDescriptionHelpFormatter)
    subs = p.add_subparsers(dest="command")

    dl = subs.add_parser("download", help=DOWNLOAD_DESC,
                         description=DOWNLOAD_DESC, epilog=DOWNLOAD_EPILOG,
                         formatter_class=argparse.RawDescriptionHelpFormatter,
                         **title("download"))
    _add_download_args(dl, w)

    cv = subs.add_parser("convert", help=CONVERT_DESC,
                         description=CONVERT_DESC, epilog=CONVERT_EPILOG,
                         formatter_class=argparse.RawDescriptionHelpFormatter,
                         **title("convert"))
    _add_convert_args(cv, w)
    return p


def to_options(args) -> Options:
    return Options(
        directory=args.directory, recursive=args.recursive, only=args.only,
        limit=args.limit, force=args.force, retry_failed=args.retry_failed,
        dry_run=args.dry_run,
        workers=args.workers, rps=args.rps, cooldown=args.cooldown,
        search_limit=args.search_limit, min_score=args.min_score,
        lyric=not args.no_lyric, cover=not args.no_cover,
        translation=not args.no_translation,
        cover_size=args.cover_size, encoding=args.encoding,
        embed_cover=args.embed_cover, write_tags=args.write_tags,
        proxy=args.proxy, rotate_ip=args.rotate_ip, clash_api=args.clash_api,
        clash_group=args.clash_group, clash_secret=args.clash_secret,
    )


def to_convert_options(args) -> convert.Options:
    return convert.Options(
        directory=args.directory, recursive=args.recursive, only=args.only,
        limit=args.limit, force=args.force, dry_run=args.dry_run,
        bitrate=args.bitrate, output=args.output, workers=args.workers,
        delete_source=args.delete_source, ffmpeg=args.ffmpeg,
    )


def _with_default_command(argv: list[str]) -> list[str]:
    """没写子命令时补上 download，保持 `nmdl --dir ...` 这种老写法可用。"""
    if argv and (argv[0] in COMMANDS or argv[0] in ("-h", "--help")):
        return argv
    return ["download"] + argv


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    argv = list(sys.argv[1:] if argv is None else argv)
    args = build_parser().parse_args(_with_default_command(argv))

    def log(*parts):
        # 必须逐行 flush：输出被重定向到文件/管道时 stdout 默认是块缓冲的，
        # 不刷就完全看不到进度。
        print(*parts, flush=True)

    if args.command == "convert":
        stats = convert.run(to_convert_options(args), log=log)
        return 0 if stats.errors == 0 else 1

    opts = to_options(args)
    if opts.rotate_ip and not opts.proxy:
        opts.proxy = "http://127.0.0.1:7890"    # 换 IP 必须走代理，规则才生效
        print("[提示] --rotate-ip 需要走代理，已默认使用 %s" % opts.proxy)

    stats = run(opts, log=log)
    return 0 if stats.errors == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
