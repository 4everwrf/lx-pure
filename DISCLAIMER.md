# 免责声明与使用条款 / Disclaimer & Usage Terms

> 本文件是对本项目 `LICENSE`（Apache License 2.0）的补充使用声明与合规说明。下载、编译、分发或使用本项目即表示您已阅读并理解以下内容。

## 一、项目性质与非官方声明

1. **轻雪音乐（LX Pure）** 是一个独立开源的 Android 原生音乐播放器与自定义脚本沙箱客户端，仅供个人学习、Android 技术研究（Kotlin / Jetpack Compose / Media3 / QuickJS）与技术交流使用。
2. 本项目**完全免费开源、非商业性质**，不接受任何形式的商业合作、收费贩卖、广告植入或付费推广；任何基于本项目的二次分发亦请遵循非商业、开源合规原则（与上游 `lyswhut/lx-music-mobile` 开源社区精神保持一致）。
3. 本项目为**非官方第三方独立实现**，仅兼容洛雪音乐（LX Music）开源自定义源协议并遵循 Apache-2.0 协议衍生使用了预加载桥接脚本 `user-api-preload.js`，与 `lyswhut/lx-music-mobile` 官方维护团队无任何隶属、授权或背书关系。

## 二、不含音源与用户责任声明

1. **本项目代码仓库、发行包（APK）及文档均不内置、不提供、不托管、不推荐任何第三方音源脚本或音频解析接口，亦不存储、缓存于云端或分发任何受版权保护的音频文件。**
2. 播放本地音频文件仅使用 Android 系统 `MediaStore` 权限访问用户设备自有文件。
3. 本项目内置的 QuickJS 沙箱仅作为通用 JavaScript 自定义源协议运行环境。用户通过本地文件、粘贴代码或自定义链接导入的任何第三方 `.js` 脚本均由用户自行获取并决策；**项目作者不对任何第三方脚本的合法性、安全性、可用性或返回内容负责，使用者需自行承担因导入或使用第三方脚本所产生的一切风险与法律责任。**
4. **请勿在本项目的 GitHub Issues、Discussions、Pull Requests 或评论区中索要、发布、粘贴或变相分享任何第三方音源脚本或下载链接，一经发现将立即删除/关闭。**

## 三、公开元数据与版权声明

1. 本项目中展示的歌单信息、榜单列表、曲目检索元数据、专辑封面链接及歌词文本等，均来源于各平台公开可访问接口（与未登录状态下的公开展示信息一致），仅作客户端聚合展示，所有名称、图像、歌词及作品知识产权均归原权利人所有。
2. 请尊重音乐创作者与版权方权益，支持正版音乐平台。禁止将本项目用于任何违反您所在国家或地区法律法规的用途。
3. 如您是相关权利人，认为本项目的代码、文档或任何内容侵犯了您的合法权益，请通过 GitHub Issues 或提交权利证明联系仓库维护者，我们将在核实后第一时间配合移除相关内容。

---

## English Summary

- **Independent & Non-Commercial**: LX Pure is an independent, unofficial, non-commercial open-source Android music player built for educational and technical research purposes under the **Apache License 2.0**. It is not affiliated with or endorsed by the official `lyswhut/lx-music-mobile` maintainers.
- **No Bundled Audio Sources**: This repository, its releases, and its documentation do **not** include, host, provide, or endorse any third-party audio source scripts, streaming APIs, or copyrighted audio files.
- **User Responsibility**: Users are solely responsible for ensuring that any local media files or third-party `.js` scripts they import into the QuickJS sandbox comply with applicable copyright laws and local regulations.
- **Copyright & Takedown**: All trademarks, track titles, artwork, and lyrics metadata belong to their respective copyright owners. If you believe any part of this repository infringes your rights, please open an issue or contact the maintainer for prompt removal.
