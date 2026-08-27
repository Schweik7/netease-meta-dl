"""图形界面入口。

用 Gooey 把 :func:`nmdl.cli.build_parser` 定义的同一套参数直接渲染成窗口，
所以 CLI 和 GUI 的功能、默认值永远是一致的——加一个参数两边同时就有了。

两个子命令会渲染成左侧边栏里的两项：「下载歌词封面」和「FLAC 转 MP3」，
各自独立，选哪个就只跑哪个。

运行日志会实时显示在窗口下方，``[12/547]`` 这样的行首会被解析成进度条。

点「开始」后真正干活的是子进程 ``python -m nmdl.cli``（见 ``target``），跟命令行版
走的是同一条路——GUI 这边只负责把参数拼成命令行。
"""

from __future__ import annotations

import sys

try:
    from gooey import Gooey, GooeyParser
except ImportError:                             # 没装 GUI 依赖时给一句人话
    Gooey = GooeyParser = None

PROGRESS_RE = r"^\[(?P<current>\d+)/(?P<total>\d+)\]"


def _target() -> str:
    r"""点「开始」时要执行的命令。

    Gooey 默认拼的是 ``sys.executable + sys.argv[0]``，而从 ``nmdl-gui.exe``
    这种 entry point 启动时 sys.argv[0] 是不带 .exe 的
    ``...\Scripts\nmdl-gui``，pythonw 打不开它（Errno 2）。所以这里直接指定
    ``python -m nmdl.cli``：参数定义两边本来就是同一个 parser，跑起来完全一致。
    """
    return '"%s" -u -m nmdl.cli' % sys.executable


def main() -> int:
    if Gooey is None:
        print("没装 GUI 依赖。请先执行：uv sync --extra gui\n"
              "（或者直接用命令行版：nmdl --help）", file=sys.stderr)
        return 2

    from .cli import build_parser

    @Gooey(
        program_name="nmdl · 本地音乐助手",
        program_description="左边选功能：给音乐下载网易云歌词封面，或把 FLAC 转成 MP3",
        sidebar_title="功能",
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
        target=_target(),
        suppress_gooey_flag=True,               # 子进程是纯 CLI，不认 --ignore-gooey
    )
    def gui_main() -> int:
        parser = build_parser(GooeyParser, gui=True)
        parser.parse_args()                     # Gooey 在这里接管，返回即窗口已关闭
        return 0

    return gui_main()


if __name__ == "__main__":
    raise SystemExit(main())
