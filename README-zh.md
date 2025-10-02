# Nym — 面向 IntelliJ IDEA 的 AI 命名与注释插件

Nym 是一个基于 IntelliJ 平台的插件，利用大语言模型（LLM）为你的代码提供更好的命名建议并生成文档注释。插件以轻量方式集成到编辑器，提供操作入口、设置页面与简单的工具窗口（展示使用统计）。

本文档介绍插件功能、安装方式、配置步骤与具体使用方法。

- IntelliJ 平台：since-build 251（IntelliJ IDEA 2025.1 及以上）
- JDK：21
- Kotlin：2.1（K2 编译器）


## 目录
- 概述
- 功能特性
- 环境要求
- 安装
  - 源码运行
  - 本地安装（ZIP）
- 配置
  - AI 提供方与 API Key
  - 性能参数
  - 偏好设置
- 使用方法
  - 生成命名（Generate Naming）
  - 生成注释（Generate Comment）
  - 自定义生成（Custom Generate）
  - 未使用代码分析（Analyze Unused Code）
  - Nym 工具窗口
- 隐私与安全
- 故障排查
- 开发与构建
- 贡献
- 许可协议


## 概述
Nym 通过集成的 LLM 提供方（OpenAI 或任意兼容 OpenAI 协议的接口）来：
- 为类、方法/函数、变量/属性提供更好的命名建议；
- 为当前元素生成文档注释（如 Javadoc/KDoc）；
- 基于你选择的文本与代码上下文进行自定义生成；
- 在工具窗口中展示基础使用统计与 Token 计数。

所有耗时任务都在后台执行，避免阻塞 IDE；敏感信息（如 API Key）通过 IntelliJ Password Safe 安全保存。


## 功能特性
- 编辑器动作（右键菜单与 Code 菜单）：
  - Nym: Generate Naming（生成命名）
  - Nym: Generate Comment（生成注释）
  - Nym: Custom Generate（自定义生成）
- 工具菜单动作：
  - Nym: Analyze Unused Code（扫描未使用文件/类/方法，输出数量汇总）
- 设置页面：配置提供方、接口地址、模型、温度、API Key、超时与并发等参数；
- 工具窗口：展示使用统计与 Token 计数，支持手动刷新；
- 国际化：英文与简体中文。


## 环境要求
- IntelliJ IDEA 2025.1 及以上（since-build 251）
- JDK 21
- 可访问所选 AI 提供方的网络（或使用本地部署的兼容 OpenAI 接口）


## 安装

### 源码运行（推荐用于开发调试）
1. 克隆本仓库；
2. 确保本机已安装并为 Gradle 选择了 JDK 21；
3. 终端执行：
   - Windows：`gradlew.bat runIde`
   - macOS/Linux：`./gradlew runIde`
4. 将启动带有 Nym 的沙箱 IDE。

### 本地安装（ZIP）
1. 构建插件 ZIP：
   - Windows：`gradlew.bat buildPlugin`
   - macOS/Linux：`./gradlew buildPlugin`
2. 在 IntelliJ IDEA 中：Settings/Preferences > Plugins > 右上角齿轮 > Install Plugin from Disk…
3. 选择 `build/distributions/` 目录下生成的 ZIP。


## 配置
打开 Settings/Preferences，进入 Tools > Nym（显示为 “Nym”），配置以下内容：

### AI 提供方与 API Key
- AI Provider：选择 “OpenAI” 或 “Custom”（兼容 OpenAI 协议的接口）。
- API Endpoint：
  - OpenAI：默认 `https://api.openai.com/v1`，如需可自定义；
  - Custom：填写兼容的基础地址（例如自建网关）。
- API Key：通过 IntelliJ Password Safe 安全保存；可显示/隐藏/清除。
- Model name：例如 `gpt-4o-mini`（默认）。自定义提供方请填写其支持的模型名。
- Temperature：0.0–1.0（UI 以 0–100 滑块呈现），用于控制输出的确定性与创造性。
- Test Connection：一键验证提供方/地址/密钥/模型是否可用。

### 性能参数
- Request timeout (ms)：单次请求超时（默认 60,000 ms）。
- Max concurrent requests：全局最大并发（默认 6），避免影响 IDE 流畅度。
- Cache max entries：内存缓存上限条目数。
- Memory threshold (MB)：内存阈值（用于后续自适应节流）。

### 偏好设置
- Generation language：AUTO/EN/ZH，用于控制生成内容的语言；
- UI language：AUTO/EN/ZH，用于控制插件界面文案（AUTO 随 IDE 语言，非中文回落至英文）；
- Naming style：CAMEL_CASE/SNAKE_CASE，作为命名生成与校验提示；
- Comment format：LINE/JAVADOC/JSDOC。


## 使用方法

### 1）生成命名（Generate Naming）
- 将光标放在 Java/Kotlin 文件中的类、方法/函数、变量/属性上；
- 通过以下任一入口：
  - 编辑器右键 > Nym: Generate Naming
  - Code 菜单 > Nym: Generate Naming
- Nym 会分析周边代码上下文并请求 LLM 返回若干命名建议；
- 结果以通知形式展示。请选择合适的名称并手动执行重命名（Refactor > Rename）。

### 2）生成注释（Generate Comment）
- 将光标放在尚未编写注释的类或方法/函数上；
- 通过以下任一入口：
  - 编辑器右键 > Nym: Generate Comment
  - Code 菜单 > Nym: Generate Comment
- Nym 根据上下文生成文档注释（根据偏好选择 Javadoc/KDoc 等），并插入到目标元素上。

### 3）自定义生成（Custom Generate）
- 在编辑器中选中一段文本，作为自定义提示（示例：“解释该算法”）；
- 运行：编辑器右键 > Nym: Custom Generate；
- Nym 会结合你的选择和局部代码上下文，返回 AI 结果并以通知形式展示，便于复制使用。

### 4）未使用代码分析（Analyze Unused Code）
- 入口：Tools 菜单 > Nym: Analyze Unused Code；
- 扫描工程并统计未使用的文件/类/方法数量，以简要汇总形式输出，不会自动执行任何重构。

### 5）Nym 工具窗口
- 入口：View > Tool Windows > Nym（右侧）；
- 展示使用统计：今日/总计次数、成功率、平均耗时，以及 Token 使用（今日/本周/本月/累计）。点击 Refresh 刷新。


## 隐私与安全
- API Key 通过 IntelliJ Password Safe 安全存储，不以明文写入配置文件；
- Nym 在本地解析 PSI/AST、构建精简上下文；当你显式触发动作时，相关的代码上下文可能会发送至你配置的提供方。若公司策略禁止外发代码，请勿启用插件；
- 网络/IO/耗时任务均在后台执行，支持取消，避免阻塞 EDT（UI 线程）。


## 故障排查
- 连接测试失败：
  - 检查提供方（OpenAI/Custom）、接口地址、API Key 与模型名；
  - 排查网络/代理/防火墙；可用 curl/Postman 先行验证连通性；
  - 注意提供方限流或凭据失效；
- 动作不可用或无响应：
  - 确认索引已完成（Dumb 模式下会回避依赖索引的能力），IDE 变为 “Smart” 后重试；
  - 生成命名：光标需位于类/方法/变量；生成注释：光标需位于类/方法；自定义生成：需选中文本；
- 超时或响应缓慢：
  - 增大超时、降低并发、尝试更轻量的模型；
- 日志与通知：
  - 可查看 IDE 日志与 Nym 通知；敏感数据不会被写入日志。


## 开发与构建
- 构建工具：Gradle + IntelliJ Platform Gradle Plugin；
- 启动沙箱：`gradlew.bat runIde`（Windows）或 `./gradlew runIde`（macOS/Linux）；
- 构建 ZIP：`gradlew.bat buildPlugin` 或 `./gradlew buildPlugin`；
- 目录结构（Kotlin）：core、ai、domain、data、presentation、integrations、platform；
  - 资源：META-INF/plugin.xml、messages、icons；
- 代码规范摘要：Kotlin 2.1；避免通配符导入；优先空安全与 internal 可见性；复杂逻辑需以中文说明“为什么”；必要时编写测试。


## 贡献
欢迎提交贡献。请遵循 Conventional Commits，确保可编译、静态检查与测试通过。新增依赖需记录于 `docs/3rd_packages_recordings.txt`（name:version:reason），并审查许可证。


## 许可协议
本仓库暂未提供显式许可协议。除非另有说明，版权归作者所有。如需明确授权范围，请提交 issue 或联系维护者。
