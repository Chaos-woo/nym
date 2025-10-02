# nym 项目编码规范（IntelliJ 平台插件 + Kotlin）

本规范适用于 nym（AI 驱动的编码命名与注释工具）的 IntelliJ 平台插件开发。

## 1. 项目定位与目标
- 类型：IntelliJ IDEA 插件（基于 IntelliJ 平台）。
- 作用：提供代码命名建议与注释生成。
- 目标：稳定、可维护、可扩展；优秀 UX；注重性能与隐私。

## 2. 环境与版本
- JDK：Java 21（语言级别与编译目标一致）。
- Kotlin：2.1（K2 编译器）。
- 构建：Gradle（gradlew），Kotlin DSL。
- 平台：IntelliJ 平台版本由构建脚本统一管理，使用 since/until 控制兼容范围。

## 3. 目录与模块（建议）
- src/main/kotlin/com.cw2.nym
    - core：异常、结果类型、日志、时间工具、序列化、DI。
    - ai：Provider 接口与实现、限流、重试、缓存、统计。
    - domain：命名策略、注释模板、规则引擎接口。
    - data：设置与持久化（PersistentState）、HTTP 客户端。
    - presentation：Actions、ToolWindow、Settings、Notifications、Intentions、Inspections。
    - integrations：PSI、VFS、Index、Refactoring、Editor 交互。
    - platform：服务注册、生命周期、线程约束。
- src/main/resources：META-INF/plugin.xml、messages/、icons/
- src/test/kotlin：单元/集成/UI 测试
- docs/：文档与变更记录
- .run/：运行配置

建议采用 feature-first 组织子包；公共能力在 core；IDE 能力在 integrations/platform。

## 4. Kotlin 代码风格
- 命名：类/对象 PascalCase；函数/属性 lowerCamelCase；常量 UPPER_SNAKE_CASE；语义明确（如 isEnabled、maxRetries）。
- 空安全：默认非空；必要时 ?；优先 ?. 与 ?:，避免 !!。
- 不变性：优先 val；函数小而专一；善用 map/flatMap/let/apply/run/also/takeIf。
- 数据：data class + @Serializable。
- 可见性：默认 internal；文件私有 private；谨慎暴露 public API。
- 表达式体：短小函数使用 = 表达式体。
- 注释：复杂业务用简体中文解释“为什么”；公共 API 使用 KDoc。
- 格式化与检查：统一 IDE 格式；ktlint + Detekt；禁用通配符导入；建议行宽 120。

## 5. 插件开发要点
- 生命周期与扩展点：plugin.xml 仅注册必要扩展；使用 applicationService/projectService 管理状态；重任务延迟到后台执行。
- UI/交互：
    - Action 轻量无阻塞；重任务用 ProgressManager/BackgroundTask，支持取消与进度。
    - ToolWindow 延迟加载；提供刷新与错误展示/重试。
    - Settings 基于 Configurable + PersistentStateComponent；变更即时校验。
    - Notifications 分组与分级（INFO/WARN/ERROR）。
- PSI/索引/Dumb 模式：
    - Dumb 模式避免索引依赖；需等待索引使用 DumbService.runWhenSmart。
    - ReadAction 读取 PSI；WriteCommandAction + CommandProcessor 写入 PSI。
- 线程与性能：
    - 禁止在 EDT 执行网络/IO/耗时任务；UI 更新仅在 EDT。
    - 后台任务可取消、有限时；及时释放监听与订阅，避免泄漏。
- 兼容性：优先稳定 API；谨慎使用内部/实验 API；兼容范围集中管理。

## 6. AI 调用策略
- Provider 设计：统一接口（Completion/Chat/Embeddings）；通过工厂或 DI 选择实现（如 OpenAI、Azure）。
- 可靠性：指数退避重试（幂等）、令牌桶限流、连接/读取/整体超时；失败降级与可解释提示。
- 提示与上下文：模板标准化；区分系统/用户/代码上下文；控制长度并可分片。
- 缓存：可配置 TTL；统计命中率；手动清理入口。
- 度量：延迟、错误率、令牌消耗等指标可视化。

## 7. 错误与日志
- 错误模型：sealed class/Result 表达网络、权限、限流、超时、取消、平台限制等。
- 日志：IntelliJ Logger/SLF4J；分级；禁止输出敏感数据；保留异常 cause。
- 用户提示：Notification 展示摘要；详情在 ToolWindow；提供“重试/查看日志/反馈”。

## 8. 配置与持久化
- 使用 PersistentStateComponent 保存设置，含版本号与迁移。
- 敏感字段使用 IDE 安全存储或环境变量；导入导出需确认。
- 默认值安全；首次运行提供引导。

## 9. 构建与依赖
- 统一版本与仓库；禁止动态版本与快照。
- 新依赖必须记录在 docs/3rd_packages_recordings.txt：name:version:reason；评估许可证、体积与维护风险。
- 编译器严格检查（JSR305 严格等）；CI 可启用 warnings as errors（逐步推进）。

## 10. 测试规范
- 层次：
    - 单元：规则引擎、模板、核心算法（无 IO）。
    - 组件/集成：服务、持久化、AI Provider（MockWebServer/Test Double）。
    - UI/功能：Actions、Settings、ToolWindow（IntelliJ Test Framework）。
- 原则：独立、可重复、确定性；不依赖公网；覆盖边界与错误路径。
- 命名：should_行为_条件_期望。
- 覆盖率目标：domain/core/ai 行覆盖 ≥ 80%，分支 ≥ 60%。

## 11. 代码评审与提交
- 分支：feature/*、fix/*、chore/*；PR 合并。
- 提交：Conventional Commits（feat/fix/docs/chore/refactor/test/build/ci…）并说明动机与影响，关联 issue。
- PR 要求：编译/静态检查/测试通过；评审关注 API 稳定、线程安全、性能与 UX；同步文档与迁移说明。

## 12. 性能与内存
- 热路径减少分配与锁竞争；复用缓冲；懒加载与分页。
- 大工程/大文件分批处理与延迟策略；索引期避免重任务。
- 周期任务可取消；释放资源；按需进行内存/CPU 分析。

## 13. 本地化与可访问性
- 文案集中于 messages 资源；键名规范化。
- 遵循可访问性：键盘可用、对比度与可读性、AccessibleContext 描述。
- 优先平台标准组件与样式。

## 14. 文档
- 为公共 API、扩展点、配置项提供简明文档与示例。
- docs/ 维护变更记录、破坏性变更与迁移指南。
- 复杂算法/提示模板记录设计权衡与限制（中文说明）。

## 15. 安全与隐私
- 网络：支持代理；遵循用户/企业策略；外发需用户知情并可关闭。
- 许可证：检查兼容性；发布前复核。
- 隐私：默认本地处理代码；外发前提醒并征得许可；提供总/细粒度开关。

## 16. 开发流程
- 前期：明确场景/数据/交互；登记依赖与评估。
- 开发：小步提交；区分 EDT/后台；防御性编程与必要断言。
- 提交前：编译/检查/测试全绿；关键路径自测；更新版本与文档。
- 发布：更新版本号、changelog、since/until；沙箱与多版本验收。

## 17. 速查清单
- 禁止在 EDT 做网络/IO/耗时；UI 仅 EDT。
- PSI 读：ReadAction；写：WriteCommandAction + CommandProcessor。
- Dumb 模式避索引依赖；必要时 runWhenSmart。
- 背景任务可取消并显示进度；合理超时与错误提示。
- 日志不含敏感信息；异常保留 cause。
- 依赖新增需记录 name:version:reason 与许可证审查。
- 复杂逻辑必须写中文注释解释“为什么”。

## 18. MCP
- 编码前可通过内部工具检索上下文与既有实现（如 context7）；避免重复造轮子。