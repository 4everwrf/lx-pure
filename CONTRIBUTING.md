# 参与贡献 / Contributing to LX Pure

感谢您关注并考虑为 **轻雪音乐（LX Pure）** 贡献代码、反馈问题或改进文档！

## 红线原则（提交前必读）

1. **严禁出现任何第三方音源**：请勿在代码、测试用例、文档、Issue、Discussion 或 PR 中内置、硬编码、分享、索要或链接任何第三方音源脚本、解析服务地址或受版权保护的音频内容。违反该规则的内容将被直接拒绝并删除。
2. **遵守开源协议**：本项目基于 **Apache License 2.0** 开源，提交代码即默认同意按 Apache-2.0 许可贡献（详见 [LICENSE](./LICENSE) 与 [DISCLAIMER.md](./DISCLAIMER.md)）。
3. **保护第三方版权与上游归属**：如引入第三方开源库或衍生代码，请确认其协议与 Apache-2.0 兼容，并同步更新 [NOTICE](./NOTICE)。

## 开发与提交流程

1. Fork 本仓库并从 `main` 分支切出特性分支（如 `feat/xxx` 或 `fix/xxx`）。
2. 在本地使用 JDK 17 与 Android SDK 35 进行开发：
   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew assembleDebug
   ```
3. 保持代码风格符合 Kotlin Official Style（`kotlin.code.style=official`），尽量为协议解析、调度与数据逻辑补充单元测试。
4. 确认提交中不包含任何本地路径、签名密钥（`*.jks` / `keystore.properties`）或敏感凭据。
5. 提交 Pull Request，清晰描述改动背景、方案与测试验证结果。

## 提交 Issue 建议

- **Bug 反馈**：请提供设备型号、Android 版本、应用版本号、复现步骤以及相关日志（请脱敏，勿包含个人敏感信息或第三方音源脚本内容）。
- **功能建议**：请说明使用场景、痛点及期望交互方式。
