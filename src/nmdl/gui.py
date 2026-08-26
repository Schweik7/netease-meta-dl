"""图形界面入口。

用 Gooey 把 :func:`nmdl.cli.build_parser` 定义的同一套参数直接渲染成窗口，
所以 CLI 和 GUI 的功能、默认值永远是一致的——加一个参数两边同时就有了。

运行日志会实时显示在窗口下方，``[12/547]`` 这样的行首会被解析成进度条。
"""

from __future__ import annotations

import sys

try:
    from gooey import Gooey, GooeyParser
except ImportError:                             # 没装 GUI 依赖时给一句人话
    Gooey = GooeyParser = None

from .cli import to_options
from .core import run

PROGRESS_RE = r"^\[(?P<current>\d+)/(?P<total>\d+)\]"


def _run(args) -> int:
    opts = to_options(args)
    if opts.rotate_ip and not opts.proxy:
        opts.proxy = "http://127.0.0.1:7890"
        print("[提示] 换 IP 需要走代理，已默认使用 %s" % opts.proxy, flush=True)

    def log(*parts):
        print(*parts, flush=True)               # Gooey 靠读 stdout 刷新界面

    stats = run(opts, log=log)
    return 0 if stats.errors == 0 else 1


def main() -> int:
    if Gooey is None:
        print("没装 GUI 依赖。请先执行：uv sync --extra gui\n"
              "（或者直接用命令行版：nmdl --help）", file=sys.stderr)
        return 2

    from .cli import build_parser

    @Gooey(
        program_name="网易云歌词封面批量下载",
        program_description="扫描目录里的音乐，按「歌手 - 歌名」匹配网易云，下载歌词和封面",
        language="chinese",
        encoding="utf-8",
        default_size=(920, 760),
        progress_regex=PROGRESS_RE,
        progress_expr="current / total * 100",
        hide_progress_msg=False,
        timing_options={"show_time_remaining": True, "hide_time_remaining_on_complete": True},
        clear_before_run=True,
        show_restart_button=True,
        richtext_controls=False,
        tabbed_groups=True,
    )
    def gui_main() -> int:
        parser = build_parser(GooeyParser, gui=True)
        return _run(parser.parse_args())

    return gui_main()


if __name__ == "__main__":
    raise SystemExit(main())
