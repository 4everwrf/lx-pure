# 轻雪音乐 (LX Pure)

<p align="center">
  <strong>Android（Kotlin + Jetpack Compose + Media3 + QuickJS）与 iOS（SwiftUI + AVFoundation + JavaScriptCore）双端原生极简音乐播放器</strong>
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-blue.svg" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/iOS-16.0%2B%20(SwiftUI)-000000?logo=apple&logoColor=white" alt="iOS 16.0+">
  <a href="https://github.com/4everwrf/lx-pure/actions/workflows/android-ci.yml"><img src="https://github.com/4everwrf/lx-pure/actions/workflows/android-ci.yml/badge.svg" alt="Android CI"></a>
  <a href="https://github.com/4everwrf/lx-pure/actions/workflows/ios-ci.yml"><img src="https://github.com/4everwrf/lx-pure/actions/workflows/ios-ci.yml/badge.svg" alt="iOS CI"></a>
  <a href="https://linux.do/"><img src="https://img.shields.io/badge/Community-LINUX%20DO-1C1C1E?logo=linux&logoColor=white" alt="LINUX DO 社区"></a>
</p>

> **重要说明（使用前请阅读）**
> - 本项目为**独立、非官方**的第三方原生 Android & iOS 客户端，基于 **Apache License 2.0** 开源，与 `lyswhut/lx-music-mobile` 官方维护团队无隶属或背书关系。
> - 本应用仅作为**本地音乐播放器**与**自定义脚本沙箱客户端**，**不内置、不提供、不推荐任何第三方音源脚本或音频资源**。请在使用前阅读完整的 [免责声明与使用条款](./DISCLAIMER.md)。
> - 请勿在 Issues / Discussions / PR 中索要、分享或贴出任何第三方音源脚本或链接。

---

## 核心特性

- **极简三栏架构与流体光影视觉（Android & iOS 一致体验）**
  - Android 采用纯 Jetpack Compose + Material 3，iOS 采用原生 SwiftUI，统一收敛为 `音乐库 / 发现 / 搜索` 三栏导航。
  - 自动提取专辑封面主色调驱动动态流体氛围背景，支持浅色 / 深色 / 跟随系统主题切换。
- **原生自定义源脚本沙箱（100% 兼容洛雪 `.js` 协议）**
  - Android 内置独立线程 QuickJS 运行时；iOS 直接基于系统原生 `JavaScriptCore` (`JSContext`) 运行 `user-api-preload.js`，零第三方 JS 引擎依赖。
  - 支持通过 **在线链接 / 本地 `.js` 文件 / 粘贴脚本源码** 三种方式自行导入脚本。
  - 支持多源启用、优先级排序、连通性测速、在线脚本更新检查，以及播放失败时的并发竞速与自动换源兜底。
- **原生音频引擎与低内存优化**
  - Android 基于 `AndroidX Media3 (ExoPlayer + MediaSessionService)`；iOS 基于 `AVFoundation (AVPlayer + AVAudioSession)` 与 `MediaPlayer (MPRemoteCommandCenter + MPNowPlayingInfoCenter)`，完整支持后台播放、锁屏控制与蓝牙切歌，支持睡眠定时与切歌预加载。
  - 内置 300MB 磁盘 LRU 边听边存音频缓存与 64MB 封面缓存（24MB 内存上限），支持移动网络智能省流（4G/5G 自动切换 128K，Wi-Fi 畅享偏好无损）。
- **聚合元数据与歌词同步**
  - 支持多平台曲目信息聚合去重、精选歌单与热门榜单浏览、外部歌单分享链接导入。
  - 支持 LRC / YRC 逐行歌词与双语翻译歌词同步展示（点按歌词行跳转进度）。
- **本地音乐与离线管理**
  - Android 通过系统 `MediaStore` 扫描设备本地音频；iOS 支持通过系统“文件”选择器导入或放入 `文件 → 我的 iPhone → 轻雪音乐 → LocalMusic` 扫描播放（FLAC / MP3 / M4A / WAV 等），均优先命中本地与离线已下载文件。

---

## 技术栈

| 模块 | Android 端 (`app/`) | iOS 端 (`ios/`) |
| :--- | :--- | :--- |
| 语言与并发 | Kotlin 2.0, Coroutines, Flow | Swift 5.9, Swift Concurrency (`async/await`) |
| UI 框架 | Jetpack Compose (Material 3), Palette, Coil | SwiftUI, CoreGraphics 主色调提取, `NSCache` + `URLCache` |
| 音频播放与后台 | AndroidX Media3 1.4.1 (ExoPlayer, MediaSessionService) | AVFoundation (`AVPlayer`, `AVAudioSession`), MediaPlayer 锁屏控制 |
| 脚本沙箱 | QuickJS Android Wrapper 2.4.0 | 系统原生 `JavaScriptCore` (`JSContext` + 独立 `JSVirtualMachine`) |
| 加解密与网络 | OkHttp 4.12.0, javax.crypto | `URLSession`, `NWPathMonitor`, `CryptoKit`, `CommonCrypto`, `Security` |
| 数据持久化 | Room 2.6.1 (KSP), DataStore Preferences | 原子化 JSON 存储 (`LibraryStore`), `UserDefaults` (`SettingsStore`) |
| 最低系统要求 | Android 8.0 (API 26)+，Target SDK 35 | iOS 16.0+ (iPhone / iPad) |

---

## 快速开始

### 1. 下载安装

前往 [GitHub Releases](https://github.com/4everwrf/lx-pure/releases) 下载最新版本的纯净版安装包：

- **Android**：下载 `lx-pure-vX.Y.Z-release.apk` 直接安装。
- **iOS**：下载 `lx-pure-ios-vX.Y.Z-unsigned.ipa`（Ad-Hoc 签名），可通过 **巨魔商店（TrollStore）**、**AltStore**、**SideStore**、**Sideloadly** 或其他自签工具安装至 iOS 16+ 设备。

> 首次安装后，如需使用自定义源协议功能，请在应用内前往 **设置 → 自定义音源管理**，通过本地 `.js` 文件、粘贴脚本代码或自定义 URL 导入您自行准备的合规脚本；本地音乐可直接通过 **音乐库 → 本地音乐** 导入/扫描播放。

### 2. Android 从源码构建

环境要求：**JDK 17** + **Android SDK (Compile SDK 35)**

```bash
git clone https://github.com/4everwrf/lx-pure.git
cd lx-pure

./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Windows PowerShell 可将 `./gradlew` 替换为 `.\gradlew.bat`（Release 建议追加 `--no-daemon --max-workers=2`）。

可选：在项目根目录创建 `keystore.properties`（已被 `.gitignore` 忽略，请勿提交）或通过环境变量 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 注入 Release 签名。

### 3. iOS 从源码构建

环境要求：**macOS + Xcode 16+** + **XcodeGen** (`brew install xcodegen`)

```bash
cd ios
xcodegen generate
open LXPure.xcodeproj
```

或在终端运行单元测试与未签名 Release 构建：

```bash
cd ios
xcodegen generate
xcodebuild -project LXPure.xcodeproj -scheme LXPure -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' test
```

> 如需在 GitHub Actions Release 中输出证书签名的 `.ipa`，可在仓库 Secrets 中配置 `IOS_CERT_P12_BASE64`、`IOS_CERT_PASSWORD`、`IOS_PROVISION_PROFILE_BASE64`；未配置时自动产出 Ad-Hoc 未签名 `.ipa`。

---

## 项目结构

```text
├── app/                         # Android 原生应用 (Kotlin + Jetpack Compose + Media3 + QuickJS)
│   └── src/main/
│       ├── assets/script/user-api-preload.js
│       └── java/com/lxpure/music/{core,data,domain,ui}/
└── ios/                         # iOS 原生应用 (SwiftUI + AVFoundation + JavaScriptCore)
    ├── project.yml              # XcodeGen 工程描述
    ├── LXPure/
    │   ├── Resources/user-api-preload.js
    │   ├── App/                 # 入口与依赖容器
    │   ├── Domain/              # 跨模块领域模型与 LX 协议序列化
    │   ├── Data/                # 持久化存储与设置
    │   ├── Core/                # JavaScriptCore 沙箱、多平台聚合 SDK、AVPlayer、缓存与下载
    │   └── UI/                  # SwiftUI 三栏界面、流体背景、沉浸播放页与音源管理
    └── LXPureTests/             # XCTest 单元测试（含真实 JSC 沙箱端到端测试）
```

---

## 开源协议与致谢

- 本项目基于 **[Apache License 2.0](./LICENSE)** 开源，详细归属说明见 [NOTICE](./NOTICE)，完整法律与免责说明见 [DISCLAIMER.md](./DISCLAIMER.md)。
- 特别致谢 **[lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile)**（Apache-2.0）：本项目遵循其开放的自定义源脚本协议，`user-api-preload.js` 衍生自该项目的预加载桥接脚本并针对 Android QuickJS 与 iOS JavaScriptCore 原生桥接进行了适配。
- 感谢 Jetpack Compose、AndroidX Media3、QuickJS、OkHttp、Coil 与 Apple SwiftUI / AVFoundation / JavaScriptCore 生态。

## 社区认可

本项目完整开源，认可并支持 **[LINUX DO 社区](https://linux.do/)** 的开源分享精神（真诚、友善、团结、专业），感谢社区为开发者提供开放、纯粹的技术交流与开源展示平台。欢迎各位佬友在社区与 GitHub 交流使用体验与改进建议。

## 参与贡献

欢迎提交 Bug 反馈、性能优化或功能改进 PR，请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)。
