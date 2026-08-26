"""网络层：全局限流 + 反限流退避。

网易云的公开接口是按 IP 限流的，触发后会返回 HTTP 200 但 body 是
``{"code":405,"msg":"操作频繁，请稍候再试"}``；而且封禁期间继续请求会不断续期，
所以这里做两件事：

1. 全局令牌桶：所有线程共用一个最小请求间隔（只限 music.163.com 的接口，
   图片 CDN 不受限）。
2. 撞到 405 时让**所有**线程一起停下来冷却，冷却时间指数递增，成功后复位。
"""

from __future__ import annotations

import random
import threading
import time

import requests

BASE_HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
    "Referer": "https://music.163.com/",
    "Accept": "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9",
}

# 命中这些 code 说明被限流了（HTTP 状态码仍然是 200）
THROTTLE_CODES = {405, 406, -460, -447, 50000005}

_local = threading.local()
_proxies: dict | None = None


def set_proxy(url: str | None) -> None:
    """设置所有请求走的代理（配合 Clash 换 IP 时必须设置）。"""
    global _proxies
    _proxies = {"http": url, "https": url} if url else None
    if hasattr(_local, "s"):
        del _local.s                            # 让本线程下次重建 Session


class Throttled(RuntimeError):
    """接口返回「操作频繁」。"""


class RateLimiter:
    """跨线程的最小间隔限流 + 全局冷却。"""

    def __init__(self, rps: float = 1.0, cooldown: float = 60.0,
                 max_cooldown: float = 600.0, on_wait=None, on_throttle=None):
        self.interval = 1.0 / rps if rps > 0 else 0.0
        self.base_cooldown = cooldown
        self.max_cooldown = max_cooldown
        self.on_wait = on_wait or (lambda secs, reason: None)
        # 撞限流时的回调，返回 True 表示已经换了出口 IP，可以立刻继续
        self.on_throttle = on_throttle
        self._lock = threading.Lock()
        self._rotate_lock = threading.Lock()
        self._next_at = 0.0
        self._blocked_until = 0.0
        self._strikes = 0
        self._last_rotate = 0.0

    def acquire(self) -> None:
        """取得一次请求许可；必要时阻塞。"""
        while True:
            with self._lock:
                now = time.monotonic()
                if now < self._blocked_until:
                    wait, reason = self._blocked_until - now, "cooldown"
                else:
                    slot = max(now, self._next_at)
                    self._next_at = slot + self.interval
                    wait, reason = slot - now, None
            if reason is None:
                if wait > 0:
                    time.sleep(wait)
                return
            self.on_wait(wait, reason)
            time.sleep(min(wait, 5.0))      # 分段睡，方便 Ctrl-C 中断

    def hit_throttle(self) -> float:
        """记一次限流，返回还需要冷却的秒数（换 IP 成功则返回 0）。"""
        with self._lock:
            self._strikes += 1
            secs = min(self.base_cooldown * (2 ** (self._strikes - 1)), self.max_cooldown)
            self._blocked_until = max(self._blocked_until, time.monotonic() + secs)

        if self.on_throttle is None:
            return secs
        with self._rotate_lock:                 # 只让一个线程去换 IP
            now = time.monotonic()
            if now - self._last_rotate < 10 or now >= self._blocked_until:
                return max(0.0, self._blocked_until - now)
            try:
                rotated = bool(self.on_throttle())
            except Exception:
                rotated = False
            self._last_rotate = time.monotonic()
        if rotated:
            with self._lock:
                self._blocked_until = 0.0
                self._strikes = 0
            return 0.0
        return secs

    def ok(self) -> None:
        with self._lock:
            self._strikes = 0


def session() -> requests.Session:
    """每线程一个 Session（保留各自的 cookie）。"""
    s = getattr(_local, "s", None)
    if s is None:
        s = requests.Session()
        s.headers.update(BASE_HEADERS)
        s.headers["Cookie"] = ("os=pc; appver=8.9.70; osver=; deviceId=; "
                               "NMTID=%d" % random.randint(10 ** 15, 10 ** 16))
        if _proxies:
            s.proxies = _proxies
        _local.s = s
    return s


def api_json(url: str, params=None, data=None, limiter: RateLimiter | None = None,
             timeout: float = 15.0, retries: int = 3, throttle_retries: int = 4) -> dict:
    """请求一个返回 JSON 的网易云接口，自动处理限流与重试。

    限流重试和普通重试分开计数：撞限流不算「失败」，只是要等（等待本身由
    ``limiter.acquire()`` 统一执行，避免多个线程各睡各的、又把封禁续上）。
    """
    last = None
    tries = throttles = 0
    while tries < retries and throttles < throttle_retries:
        if limiter:
            limiter.acquire()
        try:
            s = session()
            r = (s.post(url, data=data, timeout=timeout) if data is not None
                 else s.get(url, params=params, timeout=timeout))
            if r.status_code != 200:
                tries += 1
                last = "HTTP %d" % r.status_code
            else:
                j = r.json()
                code = j.get("code")
                if code in THROTTLE_CODES or "频繁" in str(j.get("msg", "")):
                    throttles += 1
                    secs = limiter.hit_throttle() if limiter else 60.0
                    last = "限流(code=%s)，冷却 %.0fs" % (code, secs)
                    continue
                if limiter:
                    limiter.ok()
                return j
        except Exception as e:                  # 超时 / 连接重置 / JSON 解析失败
            tries += 1
            last = "%s: %s" % (type(e).__name__, e)
        time.sleep(1.0 * tries + random.random())
    if last and last.startswith("限流"):
        raise Throttled(last)
    raise RuntimeError(last or "request failed")


def get_bytes(url: str, timeout: float = 20.0, retries: int = 3) -> bytes:
    """下载二进制（封面图走 CDN，不占接口限流额度）。"""
    last = None
    for attempt in range(retries):
        try:
            r = session().get(url, timeout=timeout)
            if r.status_code == 200 and r.content:
                return r.content
            last = "HTTP %d" % r.status_code
        except Exception as e:
            last = "%s: %s" % (type(e).__name__, e)
        time.sleep(1.0 * (attempt + 1))
    raise RuntimeError(last or "download failed")
