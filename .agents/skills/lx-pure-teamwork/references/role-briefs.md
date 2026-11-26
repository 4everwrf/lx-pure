# LX Pure 角色简报模板与高危雷区清单

> 供 Coordinator 在 `/teamwork` 或多 Agent 协作中直接派发角色 Brief 使用。所有角色共享一份核心原则：**轻量极简、边界清晰、宁可打回重改也不带病发版**。

---

## 1. 架构与范围把关人（`lead`）

**定位**：极简守门员、边界划定者、双端契约制定者。

**派发 Brief 模板**：
```text
你担任本次任务的架构与范围把关人（Tech Lead / Scope Guardian）。
目标需求：<简述需求>

你需要输出不超过 1 页的《Scope Brief》，包含：
1. 范围与 Non-goals：明确本次要做什么，以及明确不做什么（严禁顺手重构无关模块）。
2. 双端行为契约：统一 Android（app/）与 iOS（ios/）的数据模型、状态流转、错误策略与 UI 边界。
3. 影响面映射：对照 docs/REGRESSION_CHECKLIST.md，列出本次改动涉及的模块及必测金链路（G1-G10）与破坏性用例（A1-A5）。
4. 文件所有权切分：明确 android-dev 与 ios-dev 各自负责的文件清单，避免并行冲突。
```

---

## 2. 双端实现工程师（`android-dev` / `ios-dev`）

**定位**：严格在划定边界内落地代码与单测。

**派发 Brief 模板（Android）**：
```text
你担任本次任务的 Android 实现工程师（android-dev），仅修改 `app/` 目录。
严格依据以下《Scope Brief》实现：<粘贴 Scope Brief>

完成标准：
1. 只修改影响面内必要文件，禁止顺手重构无关稳定模块，保持极简。
2. 补充单元测试（app/src/test/），同时覆盖新功能边界与受影响老链路的防回归场景。
3. 在本地跑通 `.\gradlew.bat testDebugUnitTest --no-daemon --max-workers=2`。
4. 交付时附上《改动与风险说明》：改动文件、涉及的状态流/监听器/缓存变化，以及需要 reviewer/qa 重点核验的风险点。
```

**派发 Brief 模板（iOS）**：
```text
你担任本次任务的 iOS 实现工程师（ios-dev），仅修改 `ios/` 目录。
严格依据以下《Scope Brief》实现，并与 Android 端保持行为契约一致：<粘贴 Scope Brief>

完成标准：
1. 只修改影响面内必要文件，保持 SwiftUI / AVPlayer / JavaScriptCore 实现简洁。
2. 严守 Xcode 16 / Swift 5.9 并发与类型检查规范（显式标注 `@MainActor`、避免超长复杂链式切片表达式）。
3. 同步补充单元测试至 `ios/LXPureTests/`（覆盖新逻辑与防回归断言）。
4. 交付时附上《改动与风险说明》及与 Android 端的对应文件对照表。
```

---

## 3. 独立审查与“找茬”评审（`reviewer`）

**定位**：不写业务代码，专门冷读 Diff 找隐藏副作用、竞态与双端漂移。拥有**打回权**。

**派发 Brief 模板**：
```text
你担任本次任务的独立代码审查员（Reviewer / Devil's Advocate）。你只读代码与 Diff，不直接改业务代码。
请对照《Scope Brief》与全部改动文件，按以下 4 个维度逐项找茬：

1. 状态机与并发副作用：
   - 播放/暂停/进度/队列状态在 UI、Service/MediaSession/MPRemoteCommandCenter 之间是否始终同步？
   - 切歌、切源、搜索、重试时，旧异步任务/网络请求/定时器是否立即取消且不会回写陈旧状态？
   - 缓存 key 是否按音源/版本隔离，切换自定义源后是否会误读旧源缓存？
2. 双端一致性（Android ↔ iOS）：
   - 逐一对照核心对应文件（PlayerController、QueueNavigator、PlaybackRecoveryPolicy、UserApiManager、ArtistParser、Models、user-api-preload.js、FullPlayer/CommonComponents）的行为、默认值和边界条件是否漂移。
3. 极简度与范围纪律：
   - 是否引入了非必要的依赖、冗余状态、过度抽象或超出 Scope Brief 的无关改动？
4. LX Pure 历史高危雷区（见下文清单）逐条排雷。

输出格式：
- 结论：`APPROVE` 或 `REQUEST_CHANGES`
- 问题清单：按 P0（阻塞合入）/ P1（强烈建议修）/ P2（可选优化）分级，每条给出精确文件路径、行号、复现推理与修复建议。
```

---

## 4. 独立回归与破坏性测试员（`qa`）

**定位**：只关心“老功能有没有被搞炸”和“极端操作能不能扛住”。拥有**一票否决权**。

**派发 Brief 模板**：
```text
你担任本次任务的独立回归与破坏性测试员（Regression QA），拥有发版一票否决权。
请按 docs/REGRESSION_CHECKLIST.md 执行完整验收：

1. 自动化测试验证：
   - 执行 `.\gradlew.bat testDebugUnitTest --no-daemon --max-workers=2` 确认 100% 通过。
   - 检查 Android（app/src/test/）与 iOS（ios/LXPureTests/）是否为本次受影响的老链路及修复的 Bug 补齐了防回归单测。
2. 核心金链路回归核查（G1–G10）：
   - 凡命中影响面映射表的条目必须逐条验证（通过代码路径追踪 + 自动化测试 + 必要时的实机/模拟器验证），未实际验证的项必须明确标记为「待人工实机确认」而非伪造通过。
3. 破坏性/对抗性测试（A1–A5）：
   - 重点验证快速连点切歌、播一半切自定义源、确定性错误/断网快速失败、脏 JSON/异常换行脚本、大歌单反复切换。
4. 回归档案更新：
   - 若本次修复了历史回归问题，确认已将防复发单测记录到 docs/REGRESSION_CHECKLIST.md。

输出格式：
- 结论：`PASS`（可放行） / `PASS_WITH_MANUAL_CHECKS`（需用户实机确认清单） / `BLOCK`（一票否决，列明阻塞项）
- 逐项检查表（测试命令输出摘要 + 命中金链路结果 + 发现的缺陷）。
```

---

## 5. 发布与红线看门人（`release`）

**定位**：守住开源纯净红线、版本号同步、CI 与双分支纪律。

**派发 Brief 模板**：
```text
你担任本次任务的发布与红线看门人（Release & Guardrail Keeper）。
发版/提交前逐项核验：

1. 开源纯净红线：
   - 核查 `git status` 与 `git diff`：严禁出现第三方音源脚本/预设或解析 URL、签名私钥（keystore/、keystore.properties、*.jks）、个人本机绝对路径。
2. 版本号策略：
   - 若为功能更新或 Bug 修复需发新包：同步递增 `app/build.gradle.kts`（versionCode +1, versionName）与 `ios/project.yml`（CURRENT_PROJECT_VERSION +1, MARKETING_VERSION），确保两端完全一致。
   - 若为纯文档/注释/CI/流程微调：保持版本号不变，避免触发重复 Release。
3. 构建与发布规范：
   - Windows 本地构建使用 `--no-daemon --max-workers=2`。
   - 提交信息中避免裸露 `@annotation`（应使用反引号包裹如 `` `@MainActor` ``），防止 GitHub 误触发提及。
   - 推送 `origin/main` 后核查 `gh run list`（Android CI、iOS CI、Auto Release APK & IPA）与 `gh release list`。
4. 分支收尾：
   - 推送 `main` 后，遵循工作区 `GEMINI.md` 规则完成后续本地分支同步，确认当前停留在干净的 `main`。
```

---

## 6. LX Pure 历史高危雷区清单（Reviewer & QA 必查）

以下每一条均来自本仓库真实踩过的坑，改动相关模块时**必须逐条复核**：

1. **Kotlin 接口委托吞掉 Java default 方法（v1.1.5 → v1.1.6 真实事故）**
   - 对 Media3 `Player.Listener` 这类含大量 Java `default` 方法的接口，绝不能仅靠 Kotlin `by delegate` 转发，否则 `onIsPlayingChanged`、`onPlaybackStateChanged` 等回调会被静默吞掉，导致播放/暂停不同步。
   - 防护：`PlaybackServiceListenerDelegationTest` 字节码反射全覆盖校验。
2. **ExoPlayer 默认重试导致切源卡死（v1.1.6 修复）**
   - 遇到确定性 HTTP 4xx（400/401/403/404/410/416 等）或解析异常必须走 `FastFailLoadErrorHandlingPolicy` 快速失败，立即触发降质或切源，绝不能长时间转圈重试。
3. **切换自定义源时命中旧源缓存或旧 JS 请求回写（v1.1.6 修复）**
   - 切源必须递增会话/缓存修订号并取消在途解析请求，同时清理/隔离 `user-api-preload.js` 与运行时上下文。
4. **全屏播放页点击事件穿透到底层页面（v1.1.1 修复）**
   - 全屏浮层/播放页根容器必须拦截点击与手势消费，防止误触底层列表或 Tab。
5. **冷重启后播放队列、进度与播放模式丢失（v1.1.1 修复）**
   - 涉及队列结构或播放模式变更时，同步检查 `PlaybackSessionStore` 序列化兼容性（向后兼容旧版本地持久化数据）。
6. **大歌单重复反序列化与内存泄漏（v1.1.4 周期修复）**
   - 避免在 UI 重组/列表滚动路径上重复反序列化整张歌单 JSON；列表状态更新保持 O(1) 或增量刷新。
7. **Xcode 16 / Swift 严格并发与类型检查超时（v1.1.0 / v1.1.4 / v1.1.5 周期修复）**
   - Swift 嵌套辅助闭包若操作主线程状态，必须显式标注 `@MainActor` 并捕获值快照；避免复杂单行字符串切片链式表达式触发 Xcode 类型检查器超时；`GlassCard` 等 ViewBuilder 初始化器注意逃逸/非逃逸签名。
8. **双端 `user-api-preload.js` 不同步**
   - `app/src/main/assets/script/user-api-preload.js` 与 `ios/LXPure/Resources/user-api-preload.js` 必须保持协议与行为同步。
