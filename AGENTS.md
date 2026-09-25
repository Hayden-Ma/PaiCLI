# AGENTS.md

PaiCLI 仓库给 Agent / 新线程的首读入口，Codex 直接读取，Claude Code 通过 `CLAUDE.md` 的 `@AGENTS.md` 导入。这里只放每次改动都要遵守的规则；实现细节见 `docs/agents-reference.md`，评测历史见 `benchmarks/paicli-native-agentbench-v0.1/AGENTS-SNAPSHOT-ARCHIVE.md`。本文件保持在 200 行以内，新增内容优先写进上面两个文件。

## 信息优先级

代码实际行为 > `AGENTS.md` > `PAI.md` > `README.md` > `ROADMAP.md` > `CLAUDE.md`。`ROADMAP.md` 是演进方向，不代表已交付。

## 项目快照

- 面向商业使用的 Java Agent CLI，对标 Claude Code；已交付 23 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP → 长上下文 → Chrome DevTools → CDP 复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 文本 MVP）
- Banner 版本 `v16.1.0`，Maven 产物 `paicli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）
- `PAI.md` 是项目级记忆，启动时注入 system prompt，放团队共享的稳定规则；个人或会变化的事实用 `/save` 长期记忆
- 下一步：MCP OAuth / sampling / recovery
- 配套教程与面试文章的唯一源在 `docs/articles/`，改完用 `tools/sync-articles-to-javabetter.sh` 同步到 javabetter.cn 副本

### 评测（Native AgentBench）

- 2026-09-05 起用户因 Token 消耗暂停评测：**不主动恢复评测实现、真实模型调用或 Docker 运行**，只按用户逐阶段明确授权单次执行，默认不启子代理
- generator 已接 25/28 题（原权重 88/100），缺 D5/E3/E4；`formalScores=null`、`publishable=false`、`NOT_INTEGRATED` 不变，不得宣传为正式榜单
- 正式评测只评 DeepSeek V4 Flash 与 GLM-5.3-Flash；证据缺失属于评测无效，不计成 PaiCLI 的 0 分；零 provider 调用是有效失败；禁止 best-of、挑子集、自动重试
- 已知问题：E2 的 `validators/final/E2` 输出原始判定 JSON，不是统一的 `VerifierScoringReport`，`FinalSourceGeneratorTest.referencePrototypes…` 因此失败，修复需授权
- 评测工具证据从 `ToolRegistry.onPolicyToolResults` 采集（含策略拒绝、保持原顺序），不能只看 `executeTools` 的已放行子集
- 细节：`benchmarks/paicli-native-agentbench-v0.1/` 下的 RUNBOOK、FINAL-DATASET-RUNBOOK 与 AGENTS-SNAPSHOT-ARCHIVE

## 运行与命令

Java 17+ / Maven；可选 `ripgrep`（`grep_code` 优先用，缺失时回退 Java 扫描）；至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `HUNYUAN_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`。

```bash
cp .env.example .env
mvn clean package                           # 默认跳过测试（pom 中 skipTests=true）
java -jar target/paicli-1.0-SNAPSHOT.jar
java -jar target/paicli-1.0-SNAPSHOT.jar wechat setup|start
mvn test -Pquick                            # 常规回归
mvn test -Pphase16-smoke                    # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性（必须带 -DskipTests=false）
mvn test -DskipTests=false                  # 全量
```

交互命令：`/plan`、`/team`、`/hitl on|off`、`/save`、`/memory`、`/compact`、`/context`、`/init`、`/export`、`/better-harness`、`/wechat`。未识别的 `/xxx` 在 CLI 层报“未知命令”，不回退给 Agent。

`mvn test -Pquick` 当前有一批评测包（`eval.benchmark.*`）和 `TerminalMarkdownRendererTest` 的既有失败，`ToolRegistryTest.macSandboxAllowsDevelopmentRuntimesAndDevNull` 在较新 macOS 上 Seatbelt 内 JVM 以 139 退出。判断回归要对比改动前后的失败集合，不要只看总数。

## 架构

三条执行路径共享 ToolRegistry / MemoryManager / SnapshotService / ConversationLedger：ReAct（`Agent.java`，默认）、Plan-and-Execute（`PlanExecuteAgent.java`，`/plan`）、Multi-Agent（`AgentOrchestrator.java`，`/team`）。

```
src/main/java/com/paicli/
├── agent/  cli/  plan/  memory/  context/  history/  prompt/
├── tool/ (ToolRegistry)  hitl/  policy/ (PathGuard, CommandGuard, AuditLog)
├── llm/ (8 个 provider 客户端 + LlmClientFactory)  web/  browser/  mcp/  skill/  rag/
├── lsp/  snapshot/  runtime/ (api + task)  image/  wechat/  harness/  render/  tui/
└── eval/ (LLM-as-a-Judge + benchmark)
```

- 内置工具 17 个：`read_file` / `write_file` / `edit_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `browser_connect` / `browser_disconnect` / `browser_status` / `save_memory` / `load_skill` / `revert_turn`；MCP 工具动态注册为 `mcp__{server}__{tool}`
- `edit_file` 只精确替换已有文件中唯一匹配的 `old_text`；CRLF 文件会转换后重试匹配并保留原换行；新建文件用 `write_file`
- 代码理解默认 `glob_files` → `grep_code` → `read_file`；`search_code`（RAG）只做模糊语义辅助
- MCP 配置合并 `~/.paicli/mcp.json` 与项目 `.paicli/mcp.json`；检测到 `STEP_API_KEY` 自动内置 `step_search`
- 思考模型（DeepSeek V4、GLM-5.3、混元 Hy4、Kimi thinking）的 `reasoning_content` 必须随下一轮带回；各 provider 协议差异、重试策略见 `docs/agents-reference.md`
- 交互期输出走 `Renderer.stream()`，主路径不要新增裸 `System.out.println`；inline 渲染约定见 `docs/agents-reference.md`

## 关键行为约束

### Memory 与上下文

- 短期上下文只有各 Agent 实际发给 LLM 的 `conversationHistory`，不要再维护影子消息副本
- 压缩按代价从小到大：超大工具结果落盘（默认 32000 字符）→ 旧工具结果清理（`min(100k, 摘要阈值×0.6)`，只换正文、保留 `tool_call_id`）→ 摘要（Session Memory 实验路径默认关，失败回退四栏目完整摘要）。摘要阈值 `window - min(20k, window/4) - min(13k, window/8)`；栏目不全或 Token 不降时保留原历史
- 长期记忆默认 project 作用域，跨项目偏好才用 global；只存稳定事实。显式写入走去重 → 冲突检测（数字/版本差异或 Dice ≥ 0.8 不写入，由用户选 replace / force），模型不得替用户选择；超过 30 天未核实标“可能已过时”
- 长期记忆文件被多个实例和进程共享：变更必须走 `withStorageLock`（锁内重读 → 修改 → 原子写回），不要绕开直接写盘
- 自动提取只从用户原文逐字抽取，最多 3 条、只写 project、标待核实，重复或冲突一律跳过；读过 web / browser / MCP / curl 等外部内容后暂停自动写入，`save_memory` 需用户本轮明确要求记住
- system prompt 的 Memory Policy：记忆是线索不是事实，行动前对照当前文件核实
- 循环不设固定轮数上限；预算或停滞检测命中后关闭工具做一次部分完成收尾，不丢弃已有工作

### 工具执行

- 三条路径都走 `executeTools()`，不手写 for-loop
- 只有 `ToolRegistry.PARALLEL_SAFE_TOOLS` 里的只读工具并行（最多 4 个）；写文件、命令、MCP、`save_memory`、`revert_turn`、未知工具按原顺序串行；新增只读工具要加入白名单。含浏览器工具的批次整体串行。结果保持原始顺序
- 工具结果进入历史前统一经 `ToolResultBoundary.wrap()` 包成 `trust="untrusted-data"`，边界不产生任何授权；超大结果由 `ToolResultOffloader` 写入 `.paicli/tool-outputs/`

### HITL 与策略

- 拦截顺序 HitlToolRegistry → ToolRegistry → PathGuard / CommandGuard；用户无法批准策略拒绝的请求
- 需审批：`write_file`、`edit_file`、`execute_command`、`create_project`、`revert_turn` 与全部 `mcp__*`；HITL 默认关闭，`/hitl on` 开启
- PathGuard 强制路径在项目根内；CommandGuard 只是辅助黑名单，不是主防线；命令沙箱 `PAICLI_COMMAND_SANDBOX=off|auto|required`，默认 off
- 微信通道无人工审批面板，走非交互默认拒绝：只读工具允许，`execute_command` / `mcp__*` 必须命中白名单，`revert_turn` 与浏览器会话切换拒绝

### Web 与浏览器

- `web_fetch` 和浏览器导航的 URL 只能来自用户顶层原文或本分支 `web_search` 的结构化 `discoveredUrls`；工具正文、模型输出都不能扩充授权，被拒绝的调用不能换工具绕过
- Plan 并行任务和 Team worker 各用独立策略副本，只有 DAG 声明的依赖才继承上游 `web_search` URL
- 只给裸标题或主题、没有动作时先澄清，本轮不调用工具；“当前项目/文件”类问题优先本地工具
- 已知 URL 先 `web_fetch`，SPA / 防爬再用 Chrome DevTools MCP；读取优先 `take_snapshot`；公开页面不提前切 shared 模式

### Plan 与 Team

- 审阅交互：`Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划；方向键不能误判为 ESC；改动要连 raw mode 和行模式回退一起验证
- Planner 计划严格校验：非空 tasks、唯一非空字符串 id、依赖精确引用已声明 id、无环；不合法直接失败，不静默删边或猜测别名。`parsePlan` 的围栏正则与 `e1_replay.py` 逐字对齐，改动前同步重放器
- 失败且完成度 < 50% 时重规划，最多 `PAICLI_PLAN_MAX_REPLANS`（默认 2）次；到上限立即停止启动新任务、其余标 SKIPPED（与 e1_replay 的“触发点后无新工作”一致）；完成度 ≥ 50% 时失败任务的传递下游标 SKIPPED；汇总先列已完成结果，再列失败和跳过原因
- Team planner 由 `TeamPlanParser` 按同一套规则校验；Reviewer 只认 JSON 布尔 `approved: true`，不要恢复关键词兜底
- planner / reviewer 请求不暴露工具是评测审计约束，引入 `submit_*` 工具或 `response_format` 前先改审计合同

### 输出与 Skill

- 共享 `prompts/handoff.md` 要求遵守用户的严格输出格式（只要 JSON/CSV/值时不加围栏和总结），但这不是确定性保证
- Skill 索引注入 system prompt（上限 20 个 / 4KB）；`load_skill` 成功后同一轮在工具结果之后以独立 user 消息注入正文（`LoadedSkillMessages`），不要改回等下一条用户消息

## 修改联动

| 改动 | 需要一起改 |
|---|---|
| 行为 | `AGENTS.md`（规则变化时）/ `README.md` / `ROADMAP.md`（仅状态变化）/ 受影响的 `docs/articles/` |
| 命令入口 | `Main.java` + `CliCommandParser.java` + 测试 + `README.md` |
| Plan 审阅交互 | `Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证 |
| 工具集 | `ToolRegistry.java` + 三条路径和 Planner 提示词 + 文档 |
| 模型 / 接口 | 对应 Client + `LlmClientFactory.java` + `.env.example` + 文档 |
| Embedding | `EmbeddingClient` + `VectorStore` + `.env.example` + 文档 |
| Web / 搜索 | `web/` + ToolRegistry + `.env.example` + 文档 + 测试 |
| Memory | `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档 |
| HITL / 策略 | `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试 |
| MCP | `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试 |

不提交 `.env`、真实 API Key 和 `target/` 产物；保持代码可读，不过度抽象。

## 验证路径

| 场景 | 命令（均需 `-DskipTests=false`） |
|---|---|
| 工具 / 代码搜索 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| Plan / DAG | `mvn test -Dtest=ExecutionPlanTest,PlannerGraphValidationTest,PlanExecuteAgentTest` |
| Memory | `mvn test -Dtest='com.paicli.memory.*Test'` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| TUI | `mvn test -Pphase16-smoke` |

## 导航

先看本文件 → `README.md` → `Main.java`，再按任务进入模块：CLI 命令看 `Main` + `CliCommandParser`；规划看 `PlanExecuteAgent` + `Planner` + `ExecutionPlan`；工具看 `ToolRegistry` + `Agent`；模型看 `llm/*Client` + `LlmClientFactory`；Multi-Agent 看 `AgentOrchestrator` + `SubAgent`；MCP 看 `McpServerManager` + `McpClient`；渲染看 `render/Renderer` + `RendererFactory`；评测看 `eval/benchmark/BenchmarkCoordinatorMain` + `BenchmarkWorkerMain`。

## 已知边界

- 未交付：通用产品级容器/VM 沙箱、MCP OAuth + sampling + server 自动重启；benchmark 的 `DOCKER_RELAY` 只是评测隔离，不改变交互式 PaiCLI 的安全模型
- 命令沙箱默认 off：沙箱无网络且看不到 `~/.m2`、`~/.gitconfig`，`mvn` / `npm install` / `git push` 会失败；Seatbelt 内 `java -version` 仍以 139 退出
- 不要把 `ROADMAP.md` 里“将来要做”读成“现在已有”

## 维护约定

形成稳定协作规则时补进本文件，但保持精简；实现细节写 `docs/agents-reference.md`，评测进展写 benchmark 目录下的 RUNBOOK 或 ARCHIVE。
