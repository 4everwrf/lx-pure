---
name: lx-pure-teamwork
description: >-
  Use this skill for LXMusic (LX Pure) multi-role teamwork, feature development,
  bug fixes touching core playback/source/storage modules, cross-platform Android
  and iOS changes, or whenever `/teamwork` is invoked in this repository.
  Defines the 5-role lean team structure, scope/impact gates, independent review,
  Golden Path regression verification, and release guardrails.
---

# LX Pure 轻量团队协作与防回归工作流（Teamwork SOP）

LX Pure（轻雪音乐）的定位是**轻量、极简、双端原生（Android Compose/Media3/QuickJS + iOS SwiftUI/AVPlayer/JavaScriptCore）**的纯净音乐播放器。核心状态机耦合度高，严禁“加一个小功能顺手改崩老链路”。

本工作流采用 **5 个职责互斥、互相制衡的精锐角色**，通过 **4 阶段门禁** 保证“轻量不拖沓，改动不回归”。

---

## 一、角色配置（5 角色，按需合并）

| 角色 | 建议代号（`/teamwork`） | 核心职责 | 不可越界红线 |
| :--- | :--- | :--- | :--- |
| **1. 架构与范围把关人** | `lead`（或由 Coordinator 兼任） | 需求克制审查、划定变更边界、输出《影响面 & 双端契约》、终审合入 | 未明确影响面和回归项前，禁止启动编码 |
| **2. 双端实现工程师** | `android-dev` / `ios-dev` | 在划定边界内分端并行实现 + 补齐新旧链路单元测试 + 输出改动风险说明 | **禁止顺手重构无关稳定模块**；禁止只改单端不说明原因 |
| **3. 独立审查与找茬评审** | `reviewer` | 冷读 Diff，专查状态机竞态、副作用、双端漂移、极简度与历史踩坑点 | **不写业务代码**，保持独立视角；拥有**打回权** |
| **4. 独立回归与破坏性测试员** | `qa` | 跑双端单测、逐项核对《核心金链路回归清单》、执行破坏性用例、沉淀回归单测 | **不以“新功能通了”代替回归**；拥有**一票否决权** |
| **5. 发布与红线看门人** | `release`（极小改动可由 `qa` 兼任） | 核对双端版本同步、构建参数规范、`main` 纯净红线与 CI/Release 验收 | 严禁跳过合规检查或提交第三方音源/私钥 |

> 各角色可直接复制使用的任务简报（Brief）与审查清单见 [references/role-briefs.md](./references/role-briefs.md)。

### 任务分级裁减（保持轻量）

- **S 级（纯文案 / 单文件样式 / 文档微调）**：Coordinator + `qa`（核验构建与红线），无需拉满全员。
- **M 级（单端修复或非核心页面功能）**：`lead`（Coordinator） + 对应端 `dev` + `reviewer` + `qa`。
- **L 级（触及 `core/player`、`core/userapi`、`core/musicsdk`、`data/` 或双端联动功能）**：**必须全员上齐**（`android-dev` + `ios-dev` 并行，独立 `reviewer` + 独立 `qa` 双门禁）。

---

## 二、4 阶段门禁闭环

### Stage 1：定边界 & 影响面分析（Lead）

编码前必须产出不超过半页的 **Scope Brief**：
1. **需求目标与极简约束**：本次做什么、**明确不做什么**（Non-goals）。
2. **双端统一契约**：新增/变更的字段、状态流转、错误降级规则、UI 边界交互。
3. **影响面映射（Impact Map）**：对照 [docs/REGRESSION_CHECKLIST.md](../../../docs/REGRESSION_CHECKLIST.md) 的「模块改动 → 必测回归映射表」，列出本次改动波及的老链路。
4. **文件所有权划分**：Android 仅改 `app/`，iOS 仅改 `ios/`，防止并行冲突。

### Stage 2：边界内并行实现（Android Dev + iOS Dev）

- 严格按 Scope Brief 修改，**发现范围外问题只记录、不顺手改**。
- 单元测试必须同时覆盖：
  1. 新功能正常路径与边界条件；
  2. 受影响老链路的防回归断言。
- 交付物必须包含简短的 **《改动与风险说明》**：改了哪些状态流/回调/缓存/持久化，以及需要 `reviewer` 和 `qa` 重点盯防的点。

### Stage 3：双门禁独立验收（Reviewer + Regression QA 并行）

**必须同时满足以下两个门禁，任一红灯一律打回 Stage 2：**

1. **Reviewer 门禁（冷读 Diff）**：
   - 核查并发/状态机副作用（播放/暂停同步、旧异步请求取消、缓存 key 隔离、监听器转发）。
   - 核查双端逻辑一致性（Android 与 iOS 同名核心文件逐项对照）。
   - 核查 [references/role-briefs.md](./references/role-briefs.md) 中的「LX Pure 历史高危雷区」。
   - 核查代码极简度（无冗余状态、无不必要依赖、无大面积无关 diff）。
2. **Regression QA 门禁（全链路回归 + 破坏性验证）**：
   - 运行 Android 全量单测（Windows 环境遵循 `--no-daemon --max-workers=2`）：
     ```powershell
     .\gradlew.bat testDebugUnitTest --no-daemon --max-workers=2
     ```
   - 核对 iOS 单测覆盖（`ios/LXPureTests/` 是否同步新增对应测试，并检查 Swift 并发/类型检查兼容性）。
   - 逐项核对 [docs/REGRESSION_CHECKLIST.md](../../../docs/REGRESSION_CHECKLIST.md) 中命中影响面的 **核心金链路（G1–G10）** 与 **破坏性冒烟用例（A1–A5）**。
   - **Bug 永不复发规则**：凡是修复线上/历史回归 Bug，必须补充对应的自动化回归单测，并登记到 `docs/REGRESSION_CHECKLIST.md` 的「历史回归防复发档案」。

### Stage 4：红线核验与发布（Release）

1. **纯净红线扫描**：确认 `git status` 与 `git diff` 零第三方音源脚本/预设或解析 URL、零签名私钥（`keystore/`、`keystore.properties`、`*.jks`）、零个人本机绝对路径。
2. **双端版本对齐**（仅功能/修复需发新包时）：
   - `app/build.gradle.kts`：`versionCode` (+1) 与 `versionName`
   - `ios/project.yml`：`CURRENT_PROJECT_VERSION` (+1) 与 `MARKETING_VERSION`
   - 纯文档/流程/CI 调整不递增版本号。
3. **构建与 CI 核查**：
   - 发版提交标题（commit subject）及 Release 变更内容**必须使用中文**（因为 `.github/workflows/release.yml` 会直接提取 commit 标题生成 Release 更新日志），避免裸露 `@annotation`。
   - 本地必要时执行 `.\gradlew.bat assembleDebug --no-daemon --max-workers=2`（或 `assembleRelease`）。
   - 推送 `origin/main` 后核查 `gh run list`（Android CI、iOS CI、Auto Release APK & IPA 均绿灯）及 `gh release list`。
4. **分支与工作区收尾**：按项目本地规则（见 `GEMINI.md`）完成发布后的本地分支同步，确保最终停留在干净的 `main` 分支。

---

## 三、交付验收标准（Definition of Done）

- [ ] Scope Brief 边界清晰，无无关代码顺手重构
- [ ] Android 与 iOS 契约行为一致（若仅改单端，已明确说明原因）
- [ ] `reviewer` 给出明确 `APPROVE`（无未关闭的 P0/P1 隐患）
- [ ] `qa` 确认单测全绿 + 命中影响面的金链路与破坏性用例全过 + 回归 Bug 已固化单测
- [ ] `release` 确认纯净红线、版本号策略、CI 与分支状态全部合规
