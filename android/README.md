# nmdl 安卓版

把桌面版的「下载歌词封面」搬到手机上：扫描手机里的音乐，按「歌手 - 歌名」匹配网易云，
在每首歌旁边写出同名的 `.lrc` 和 `.jpg`。

匹配算法、限流策略、缓存文件格式都和桌面版一致——同一个音乐文件夹在电脑和手机上
轮流跑，`.nmdl_cache.json` 是互通的，不会重复下载。

## 和桌面版的差异

| 功能 | 桌面版 | 安卓版 |
| --- | :---: | :---: |
| 下载歌词 / 封面 | ✅ | ✅ |
| 合并中文翻译 | ✅ | ✅ |
| 匹配打分、限流退避、缓存与报告 | ✅ | ✅ |
| FLAC 转 MP3 | ✅ | ❌ |
| 写入音频标签 / 内嵌封面 | ✅ | ❌ |
| HTTP 代理、Clash 换 IP | ✅ | ❌ |

后三项没做的原因：转换依赖 ffmpeg，而 ffmpeg-kit 官方已于 2025 年初停止维护、
二进制仓库下架；写标签在安卓上没有 mutagen 的等价物；换 IP 在手机上没有对应场景。

## 构建

这台机器上没有系统 JDK，用的是单独下载的 Temurin 21：

```powershell
$env:JAVA_HOME = "D:\0-code\9-android\.jdk\jdk-21.0.11+10"
cd android
.\gradlew.bat assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

国内直连 google / gradle 基本会超时，仓库和 wrapper 都已经指向镜像
（阿里云 Maven、腾讯云 Gradle），见 `settings.gradle.kts` 和
`gradle/wrapper/gradle-wrapper.properties`。

## 测试

```powershell
.\gradlew.bat testDebugUnitTest        # JVM 单元测试
.\gradlew.bat connectedDebugAndroidTest # 真机测试（需要连着设备）
```

两套测试的期望值都是从桌面版的 Python 跑出来的，不是手算的——匹配分数直接决定
「匹配 / 未匹配」的分界，两端必须给出同样的结果。

**为什么真机测试不能省**：安卓的正则是 ICU 实现，JVM 的是 `java.util.regex`，
语法并不全等。本项目踩过一次——`(?U)` 内联标志 JVM 认、ICU 不认，于是 JVM 单元测试
全绿，装到手机上一点「开始」就崩在 `Matcher` 的类初始化里
（`PatternSyntaxException`）。现在 Unicode 词边界是用 lookaround 显式写的，
并且在 `app/src/androidTest` 里留了一份同样期望值的回归测试。

## 装到手机

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
# 或者
android-cli run --device <serial> --apks app\build\outputs\apk\debug\app-debug.apk
```

小米 / 红米（HyperOS）首次安装若报 `INSTALL_FAILED_USER_RESTRICTED`，
去开发者选项里打开「USB 安装」。

## 使用

1. 首次打开会提示授予**「所有文件访问权限」**。歌词和封面要写在歌曲文件旁边，
   Android 11 以后只有这个权限能做到；用 SAF 目录授权的话，几百个文件的遍历会慢到没法用。
2. 选择音乐目录（比如 `/storage/emulated/0/Music`）。
3. 按需勾选「歌词 / 封面 / 含翻译 / 含子目录」，点**开始**。
4. **扫描**按钮不发任何网络请求，只统计目录里有多少首歌、已经配好了多少。

跑完会在目录里留下两个文件：`.nmdl_cache.json`（进度缓存）和
`nmdl_report.txt`（没匹配上、需要人工确认的清单）。

### 几个参数的意思

- **限速（请求/秒）**：默认 1.0。网易云按 IP 限流，调高很容易撞
  `操作频繁`；撞上以后所有任务会一起冷却，冷却时间逐次翻倍。
- **最低匹配分**：默认 45，满分约 138。调高更严格，宁可漏掉也不错配。
- **并发数**：默认 3。它和限速是两回事——并发决定同时有几首在处理，
  真正的请求节奏由限速统一控制。
- **歌词带 UTF-8 BOM**：对应桌面版默认的 `utf-8-sig`，播放器兼容性最好。
