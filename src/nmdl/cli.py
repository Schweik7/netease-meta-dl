"""命令行入口。参数定义由 CLI 和 GUI 共用（GUI 只是把同一个 parser 渲染成窗口）。"""

from __future__ import annotations

import argparse
import sys

from .core import Options, run

DESCRIPTION = "批量为本地音乐下载网易云的歌词(.lrc)和封面(.jpg)"

EPILOG = """\
示例:
  nmdl                             处理当前目录，跳过已有歌词/封面的
  nmdl --dir D:\\Music --limit 10   先拿 10 首试试
  nmdl --dry-run                   只看匹配结果，不写任何文件
  nmdl --retry-failed              只重试上次没成功的
  nmdl --rotate-ip                 撞限流时用 Clash 换节点换 IP 继续
"""


def build_parser(parser_class=argparse.ArgumentParser, gui: bool = False):
    """构造参数解析器。gui=True 时给控件加上 Gooey 的 widget 提示。"""

    def w(name: str, **extra):
        """只在 GUI 模式下附加 widget 相关参数。"""
        return dict(widget=name, **extra) if gui else {}

    p = parser_class(description=DESCRIPTION, epilog=EPILOG,
                     formatter_class=argparse.RawDescriptionHelpFormatter)

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


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    args = build_parser().parse_args(argv)
    opts = to_options(args)
    if opts.rotate_ip and not opts.proxy:
        opts.proxy = "http://127.0.0.1:7890"    # 换 IP 必须走代理，规则才生效
        print("[提示] --rotate-ip 需要走代理，已默认使用 %s" % opts.proxy)
    def log(*parts):
        # 必须逐行 flush：输出被重定向到文件/管道时 stdout 默认是块缓冲的，
        # 不刷就完全看不到进度。
        print(*parts, flush=True)

    stats = run(opts, log=log)
    return 0 if stats.errors == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
