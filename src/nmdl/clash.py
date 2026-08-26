"""通过 Clash 的外部控制接口切换出口 IP。

网易云的限流是按 IP 算的。撞上限流时，与其干等几分钟，不如把 Clash 里负责网易云
流量的策略组换一个节点，换个 IP 立刻继续。

注意：策略组是系统级的，切换会影响本机其它网易云流量（比如你正开着的音乐客户端），
所以这里会记住原来的选择，并在退出时**务必**还原（正常退出、异常、Ctrl-C 都会还原）。
"""

from __future__ import annotations

import atexit
import time
import urllib.parse

import requests

# 订阅里常见的广告 / 说明条目，不是真节点
AD_HINTS = ("网址", "客服", "官网", "剩余", "到期", "流量", "订阅", "过期", "重置")


class ClashRotator:
    def __init__(self, api: str = "http://127.0.0.1:28845", group: str = "",
                 secret: str = "", proxy: str = "http://127.0.0.1:7890",
                 log=print):
        self.api = api.rstrip("/")
        self.group = group
        self.secret = secret
        self.proxy = proxy
        self.log = log
        self.original: str | None = None
        self._bad: set[str] = set()
        self._restored = False

    # -- 底层 ------------------------------------------------------------
    @property
    def _headers(self) -> dict:
        return {"Authorization": "Bearer " + self.secret} if self.secret else {}

    def _get(self, path: str) -> dict:
        r = requests.get(self.api + path, headers=self._headers, timeout=6)
        r.raise_for_status()
        return r.json()

    def _put(self, path: str, payload: dict) -> None:
        r = requests.put(self.api + path, json=payload, headers=self._headers, timeout=6)
        r.raise_for_status()

    def _group_path(self, group: str = "") -> str:
        return "/proxies/" + urllib.parse.quote(group or self.group)

    # -- 探测与选择 ------------------------------------------------------
    def detect_group(self) -> str:
        """找出实际负责 music.163.com 流量的策略组。"""
        if self.group:
            return self.group
        chain_group = ""
        try:                                    # 发一个请求，再从连接表里读它走的链路
            s = requests.Session()
            s.proxies = {"http": self.proxy, "https": self.proxy}
            r = s.get("https://music.163.com/api/song/detail/?ids=%5B2117030%5D",
                      timeout=15, stream=True)
            time.sleep(0.8)
            for con in (self._get("/connections").get("connections") or []):
                if "163" in (con.get("metadata", {}).get("host") or ""):
                    chains = con.get("chains") or []
                    if chains:
                        chain_group = chains[-1]        # 链路末位就是命中的策略组
                        break
            r.close()
        except Exception:
            pass

        proxies = self._get("/proxies")["proxies"]
        for cand in (chain_group, "NetEaseMusic", "NeteaseMusic", "网易云音乐", "Proxies"):
            if cand and proxies.get(cand, {}).get("type") == "Selector":
                self.group = cand
                return cand
        raise RuntimeError("找不到可切换的策略组，请用 --clash-group 指定")

    def nodes(self) -> list[str]:
        info = self._get(self._group_path())
        return [n for n in info.get("all", [])
                if not any(h in n for h in AD_HINTS) and n not in self._bad]

    def current(self) -> str:
        return self._get(self._group_path()).get("now", "")

    def select(self, name: str) -> None:
        self._put(self._group_path(), {"name": name})

    # -- 对外 ------------------------------------------------------------
    def prepare(self) -> None:
        """记录原始选择并注册还原钩子。"""
        self.detect_group()
        self.original = self.current()
        atexit.register(self.restore)
        self.log("[clash] 策略组 %s，当前节点 %s" % (self.group, self.original))

    def probe(self) -> bool:
        """确认当前节点能正常访问网易云接口。"""
        try:
            s = requests.Session()
            s.proxies = {"http": self.proxy, "https": self.proxy}
            s.headers.update({"User-Agent": "Mozilla/5.0 Chrome/120.0",
                              "Referer": "https://music.163.com/",
                              "Cookie": "os=pc; appver=8.9.70"})
            j = s.get("https://music.163.com/api/cloudsearch/pc",
                      params={"s": "Adele Someone Like You", "type": 1, "limit": 3},
                      timeout=15).json()
            return bool((j.get("result") or {}).get("songs"))
        except Exception:
            return False

    def rotate(self) -> str:
        """换到下一个可用节点，返回节点名；没有可用节点时抛异常。"""
        all_nodes = self.nodes()
        if not all_nodes:
            raise RuntimeError("没有可用节点了")
        now = self.current()
        start = all_nodes.index(now) + 1 if now in all_nodes else 0
        order = all_nodes[start:] + all_nodes[:start]
        for node in order:
            if node == now:
                continue
            try:
                self.select(node)
            except Exception:
                continue
            time.sleep(0.5)
            if self.probe():
                self.log("[clash] 已切换到节点 %s" % node)
                return node
            self._bad.add(node)                 # 连不上 163 的节点直接拉黑
            self.log("[clash] 节点 %s 不可用，跳过" % node)
        raise RuntimeError("所有节点都连不上网易云")

    def restore(self) -> None:
        if self._restored or not self.original:
            return
        self._restored = True
        try:
            self.select(self.original)
            self.log("[clash] 策略组已还原为 %s" % self.original)
        except Exception as e:
            self.log("[clash] 还原失败（请手动改回 %s）：%s" % (self.original, e))
