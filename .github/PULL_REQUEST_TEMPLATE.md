## 改动说明与范围边界

<!-- 请简述本次 PR 解决的问题或新增的功能，并说明明确不触及的模块 -->

- **涉及平台**：[ ] Android (`app/`)  [ ] iOS (`ios/`)  [ ] 文档/CI
- **命中影响面**（对照 [docs/REGRESSION_CHECKLIST.md](../docs/REGRESSION_CHECKLIST.md)）：<!-- 例如：G1 播放同步、G3 切源、A1 狂点切歌 -->

## 验证与防回归检查

- [ ] Android 单测全部通过（`./gradlew testDebugUnitTest`）
- [ ] Android 构建通过（`./gradlew assembleDebug`）
- [ ] 如涉及 iOS：`ios/LXPureTests` 已同步覆盖且行为与 Android 一致
- [ ] 已对照 [docs/REGRESSION_CHECKLIST.md](../docs/REGRESSION_CHECKLIST.md) 完成命中金链路与破坏性冒烟回归
- [ ] 若为 Bug 修复：已补充防复发单元测试（并在回归档案登记）

## 合规检查（必选）

- [ ] 本次提交不包含任何第三方音源脚本、音源链接或解析接口
- [ ] 本次提交不包含任何签名密钥、个人本地路径或敏感信息
- [ ] 同意按照本项目 Apache License 2.0 协议贡献代码
