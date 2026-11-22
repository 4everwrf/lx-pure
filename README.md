# 轻雪音乐 (LX Pure)

<p align="center">
  <strong>基于 Kotlin + Jetpack Compose + Media3 + QuickJS 原生构建的极简 Android 音乐播放器</strong>
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-blue.svg" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <a href="https://github.com/4everwrf/lx-pure/actions/workflows/android-ci.yml"><img src="https://github.com/4everwrf/lx-pure/actions/workflows/android-ci.yml/badge.svg" alt="Android CI"></a>
</p>

> **重要说明（使用前请阅读）**
> - 本项目为**独立、非官方**的第三方原生 Android 客户端，基于 **Apache License 2.0** 开源，与 `lyswhut/lx-music-mobile` 官方维护团队无隶属或背书关系。
> - 本应用仅作为**本地音乐播放器**与**自定义脚本沙箱客户端**，**不内置、不提供、不推荐任何第三方音源脚本或音频资源**。请在使用前阅读完整的 [免责声明与使用条款](./DISCLAIMER.md)。
> - 请勿在 Issues / Discussions / PR 中索要、分享或贴出任何第三方音源脚本或链接。

---

## 核心特性

- **极简三栏架构与流体光影视觉**
  - 采用纯 Jetpack Compose 与 Material 3 打造，收敛为 `音乐库 / 发现 / 搜索` 三栏导航。
  - 通过 `Palette` 提取专辑封面主色调驱动动态流体氛围背景，支持浅色 / 深色 / 跟随系统主题切换。
- **原生 QuickJS 自定义源沙箱**
  - 内置独立线程 QuickJS 运行时与兼容洛雪自定义源协议（`.js`）的预加载桥接层。
  - 支持通过 **在线链接 / 本地 `.js` 文件 / 粘贴脚本源码** 三种方式自行导入脚本。
  - 支持多源启用、优先级排序、连通性测速、在线脚本更新检查，以及播放失败时的并发竞速与自动换源兜底。
- **Media3 原生音频引擎与低内存优化**
  - 基于 `AndroidX Media3 (ExoPlayer + MediaSessionService)`，完整适配通知栏、锁屏媒体控制与蓝牙耳机切歌，支持睡眠定时。
  - 内置 300MB 磁盘 LRU 边听边存音频缓存与 64MB 封面缓存，配合 Coil 24MB 内存上限与后台 `onTrimMemory` 裁剪，保持低内存占用与流畅滑动。
  - 支持移动网络智能省流（4G/5G 自动切换标准音质，Wi-Fi 优先偏好音质）。
- **聚合元数据与歌词同步**
  - 支持多平台曲目信息聚合去重、精选歌单与热门榜单浏览、外部歌单分享链接导入。
  - 支持 LRC / YRC 逐行歌词与双语翻译歌词同步展示。
- **本地音乐与离线管理**
  - 支持通过系统 `MediaStore` 扫描设备本地音频（FLAC / MP3 / M4A 等）并优先命中本地/已保存文件播放。

---

## 技术栈

| 模块 | 技术选型 |
| :--- | :--- |
| 语言与异步 | Kotlin 2.0, Coroutines, Flow, kotlinx.serialization |
| UI 框架 | Jetpack Compose (BOM 2024.10.01), Material 3, Palette, Coil |
| 音频播放与缓存 | AndroidX Media3 1.4.1 (ExoPlayer, MediaSessionService, OkHttp DataSource, SimpleCache) |
| 脚本沙箱 | QuickJS Android Wrapper 2.4.0 (`user-api-preload.js` 协议桥接) |
| 数据持久化 | Room 2.6.1 (KSP), DataStore Preferences 1.1.1 |
| 网络层 | OkHttp 4.12.0 |
| 最低系统要求 | Android 8.0 (API 26) 及以上，Target SDK 35 |

---

## 快速开始

### 1. 下载安装

前往 [GitHub Releases](https://github.com/4everwrf/lx-pure/releases) 下载最新版本的纯净版 APK 安装包。

> 首次安装后，如需使用自定义源协议功能，请在应用内前往 **设置 → 自定义音源管理**，通过本地 `.js` 文件、粘贴脚本代码或自定义 URL 导入您自行准备的合规脚本；本地音乐可直接通过 **音乐库 → 本地音乐** 扫描播放。

### 2. 从源码构建

环境要求：
- **JDK 17**
- **Android SDK (Compile SDK 35)**

```bash
# 克隆仓库
git clone https://github.com/4everwrf/lx-pure.git
cd lx-pure

# 运行单元测试
./gradlew testDebugUnitTest

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK（可选配置签名）
./gradlew assembleRelease
```

Windows PowerShell 可将 `./gradlew` 替换为 `.\gradlew.bat`。

构建产物输出路径：
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

#### 可选：配置 Release 签名

如需使用自定义签名证书编译 Release 包，可在项目根目录创建 `keystore.properties`（已被 `.gitignore` 忽略，请勿提交）：

```properties
storeFile=path/to/your-release.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

也支持通过环境变量 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 注入。未配置时将自动回退至本地 Debug 签名以便本地调试。

---

## 项目结构

```text
app/src/main/
├── assets/script/
│   └── user-api-preload.js      # 衍生自 lyswhut/lx-music-mobile (Apache-2.0) 的自定义源沙箱桥接脚本
└── java/com/lxpure/music/
    ├── core/
    │   ├── download/            # 离线流式下载管理
    │   ├── localmedia/          # MediaStore 本地音频扫描
    │   ├── musicsdk/            # 公开元数据聚合、去重与歌词解析
    │   ├── network/             # 网络状态与省流音质策略
    │   ├── player/              # Media3 播放服务、控制与 LRU 缓存
    │   └── userapi/             # QuickJS 沙箱、脚本元数据解析与多源调度
    ├── data/                    # Room 数据库、DAO、DataStore 与 Repository
    ├── domain/                  # 领域模型
    └── ui/                      # 音乐库、发现、搜索、沉浸播放页、设置与音源管理
```

---

## 开源协议与致谢

- 本项目基于 **[Apache License 2.0](./LICENSE)** 开源，详细归属说明见 [NOTICE](./NOTICE)，完整法律与免责说明见 [DISCLAIMER.md](./DISCLAIMER.md)。
- 特别致谢 **[lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile)**（Apache-2.0）：本项目遵循其开放的自定义源脚本协议，且 `app/src/main/assets/script/user-api-preload.js` 衍生自该项目的预加载桥接脚本并针对 QuickJS 原生桥接进行了适配。
- 感谢 Jetpack Compose、AndroidX Media3、QuickJS、OkHttp、Coil 等优秀开源社区项目。

## 参与贡献

欢迎提交 Bug 反馈、性能优化或功能改进 PR，请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)。
