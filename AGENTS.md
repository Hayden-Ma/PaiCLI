# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `PAI.md` > 4. `README.md` > 5. `ROADMAP.md` > 6. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

2026-09-05 用户因 Token 消耗明确暂停评测。停止实现/测试/子代理，不因持续目标或自动续接而恢复；后续按用户指定阶段单次执行，默认不启子代理。交接见 `benchmarks/paicli-native-agentbench-v0.1/EVALUATION-HANDOFF-2026-09-05.md`。最后已验证的是运行手册第 47 节；之后 v12 TEAM 宿主接线只部分落盘、未编译/测试，`TeamRequestAudit.java` 缺失但已有引用，当前源码不是可构建检查点。不要把旧 jar 当成当前源码验证结果。

2026-09-07 用户授权启动阶段 2：`dev-code-retry-location` 单题 × DeepSeek V4 Flash / GLM-5.3-Flash 各一次真实 episode，均 SCORED_PASS 诊断 100、基础设施错误 0，全部 provider 证据门禁（resolved model/usage/请求指纹/cap）绿；Candidate jar `2893f10c…`（阶段 0 修复后源码）、suite 摘要与 2026-09-04 冻结一致；私有产物在 `~/.paicli/benchmark-runs/phase2-2026-09-07`（0700）。合计 11 次 LLM 调用、42,149 input + 2,294 output token。这是单题子集冒烟与链路验证，`publishable=false`（subset diagnostic run）、`formalScores=null` 不变；不自动复跑、不外推成功率。结果详见 `DEV-DIAGNOSTIC-REPORT-2026-09-06.md` 第 6 节。同日用户继续授权阶段 3 子阶段 1：补齐 `TeamRelayProtocolTest`（5 项：TEAM 绑定矩阵/事件 ACK 往返与 scope 归因/无激活拒绝/预算拒绝保留 attempt/观察失败只能以 REQUEST_FINGERPRINT_UNPROVEN 收尾）与 `TeamRelayEvidenceTest`（2 项：原生 TEAM episode 经真实帧后的封闭宿主证据 + TracingLlmClient schema 2 指纹 completeFor；观察漂移使宿主拒绝而非给分），连同既有定向组 52 项全绿，真实模型调用 0。阶段 3 子阶段 2（2026-09-08 继续）：`TeamRequestAudit` 快照 v1 自出生即含写入归属段——`WriteAttribution`（write_file 按 provider 实参 path 归属到激活/角色/步骤/attempt，successful 取自 post-policy 批次）与 `WriteConflict`（同一 path 被多个不同激活成功写入时记录双方身份，仅观察不定罪）；`TeamRelayEvidenceTest` 增至 3 项（单 worker 归属 + 双 worker 写 shared.txt 的冲突对照），`TeamRelayProtocolTest` 增至 6 项（新增：仅已观察的 BudgetFinalization 事件解锁一次无工具收尾调用，第二次收尾式调用以 EPISODE_BUDGET_EXHAUSTED 拒绝且三次 attempt 全保留），定向组 54 项全绿。阶段 3 子阶段 3a（2026-09-09 继续）：独立 Python 计分闭环——新增 `src/main/resources/benchmark/e2_team_verify.py`（与 e1/d3/d4/f 系验题器同目录、同约定：严格 JSON、从原始事件重建生命周期、从真实 provider 请求+post-policy 批次重推导写入归属并与审计段交叉核验、指纹漂移即判证据无效 exit 2；一致但行为错误是有效 0 分，自相矛盾/不完整是评测无效无数值分）与 `E2IndependentVerificationTest` 4 项控制（正确 episode 满诊断含 oracle/evidence 摘要核对、冲突 episode 有效 0 且归属一致、归属段篡改→evaluation-invalid 无分、缺失 RunExited→evaluation-invalid 无分），定向组 58 项全绿、真实模型调用 0。阶段 3 子阶段 3b（2026-09-09 继续）：E2 开发控制闭环——`E2DockerControlTest`（默认关闭，`-Dpaicli.test.e2.docker=true` 显式开启）以脚本 provider 驱动真实无网络 Docker Candidate Worker（TEAM 模式、FILE_ONLY、candidate jar `5ce1fc55…`/runner `9abfcfdb…` 为当前源码重建）与真实 Docker verifier 内的 `e2_team_verify.py`：同一冻结 oracle 判两控制，correct 满诊断 100、conflict（双 worker 写 shared.txt）有效 0 且归属段一致；oracle/验题程序/evidence 的 SHA-256 在验题前后复核不变，私有记录写 episode 内 independent-replay.json。阶段 0–3 累计定向回归 59 项（58 通过 + Docker 控制 1 项默认跳过）、真实模型 API 调用 0。E2 在开发诊断层面达成交接"recipe/执行/验题正反控制闭环"停止点；generator 正式 recipe/逐题合同接线（进入 24/28 正式计数）仍未做，需另行授权，`NOT_INTEGRATED` / `formalScores=null` / `publishable=false` 不变。

2026-09-09 用户继续授权阶段 3 子阶段 3c（E2 正式 recipe 注册）：generator catalog 将 E2 由 planned 转 implemented（TEAM + LOCAL_COMMAND、L2 权重 4 不变），新增 `team/E2FrozenOracle`（严格 schema、seeded fixture：README + spec/rules.md + check.sh）与 `finalset/generator/E2CaseMaterializer`（deterministic 参考工作区 + 合成 team-audit envelope，synthetic 明确标注非运行时证据），`e2_team_verify.py` 升级为同时收 v5 envelope（内嵌 teamAudit）与裸审计、新增 doc_consumes_dependencies 与 no_unrequested_files 检查，v4 逐题合同绑定 `team_audit`+`scoped_request_fingerprints` 证据要求（timeout 2400s/budget 300k/48 轮）。真实 generate-incomplete 全量跑通：implementedRecipes=25/28（原权重合计 88/100）、finalReady=false 保持 fail-closed，E2 contract 编译成功且生成物 wrapper+独立验题器对参考 envelope 打 100（8 项检查全真）。新增 `E2GeneratedVerifierTest` 3 项；相关定向回归 99 项全绿（12 跳过为既有平台限制与 opt-in）。D5/E3/E4 仍未物化，正式批次与生产准入继续关闭。

2026-09-06 用户恢复执行，交接阶段 0 + 1 已完成：补齐缺失的 `relay/TeamRequestAudit.java`（TEAM 宿主审计：scope 绑定激活、输入索引/指纹/系统提示一致性、逐激活工具批次对账、预算收尾判定、封闭生命周期断言、0600 私有快照），源码恢复为可编译检查点；最小定向验证 45 项全绿（`TeamRequestAuditTest` 16 项含 9 类漂移/伪造负对照 + relay/协议/tracing/证据门禁 29 项），真实 API 调用 0，未改其他生产文件。新增开发诊断汇总 `benchmarks/paicli-native-agentbench-v0.1/DEV-DIAGNOSTIC-REPORT-2026-09-06.md` 与读者教程 `docs/paicli-agentbench-why-tutorial.md`。`TeamRelayProtocolTest` / `TeamRelayEvidenceTest` 仍缺，E2 宿主证据与独立计分未完成；阶段 2+（真实模型调用、补 D5/E2/E3/E4、正式批次）仍需用户逐阶段明确授权；`formalScores=null` / `publishable=false` / `NOT_INTEGRATED` 不变。

2026-09-05 模型范围修订：用户明确只评测 DeepSeek V4 Flash 与 GLM-5.3-Flash。新 batch v4 / plan v5 精确登记双模型 168 次（28 × 2 × 3）；旧 batch v3 / plan v4 的三模型 252 次仍保持兼容，不删改其冻结文件与结果。以下三模型/缺 Hy4 条目保留历史语境，不再作为新 v4 的前置条件。新合同仍要求完整 28 题、三次重复、原权重/预算及证据门禁；`NOT_INTEGRATED` / `formalScores=null` / `publishable=false` 不变。F3 双模型入口已改范围，目前仅预检、真实调用被外部数据传输授权拦住。详见运行手册第 45–46 节。

- 项目名：`PaiCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 23 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP）
- `PAI.md` 是 PaiCLI 的项目级记忆文件：启动时自动注入 system prompt，适合团队共享的长期稳定规则；个人/会变化的经验继续用 `/save` 长期记忆。
- 内置 `/better-harness [quick|normal] [--inline]`：并行审查 Session / Project Harness / Agent Customize 三路脱敏证据，默认输出到 `.paicli/better-harness/<run-id>/`。
- `eval/` 提供 LLM-as-a-Judge 核心库：Rubric 逐维评分由 Java 计算加权总分，Pairwise 通过交换 A/B 位置复评来暴露位置偏见；尚无 `/eval` 交互命令和人工一致率数据。
- `eval/benchmark/` 已有独立的 dev-pilot Coordinator / Worker，配套 8 题 `FILE_ONLY` 开发集、Docker verifier、DeepSeek V4 Flash / 混元 Hy4 preview / GLM-5.3-Flash 精确模型锁和 owner-only 私有 raw 证据。除宿主 `HOST_DEV` 外，`DOCKER_RELAY` 会把 Candidate 放进无网络、只读根文件系统和资源限额容器，真实 provider 与密钥只留在宿主 relay；可信 thin runner 与 Candidate fat jar 独立校验、快照和只读挂载，runner 固定在 classpath 前。Worker 已按 manifest 分发 ReAct / Plan / Team，并提供零工具、只读、文件编辑和本地命令四种静态工具面；当前公开集仍只有 ReAct，Plan / Team 专用轨迹证据未完成。正式合同 v3 对三模型统一冻结 1M context 与每次 16384 output；服务端 model、usage、请求指纹或 cap 证据不完整属于 evaluation-invalid，不得算成 PaiCLI 的 0 分，零 provider call 则仍是 Candidate 有效失败。完整 dev.2 横评早于 Docker relay/身份门禁；后续 DeepSeek 与 GLM 单题容器冒烟已闭环 resolved model、usage 和请求指纹，但仍是 subset 诊断。final generator 当前已接 25/28 题物化代码（E2 于 2026-09-09 注册，原权重合计 88/100）（F3 正式控制已验证，非模型成绩），含已接冻结 mock 与独立 verifier 的 D1/D2/D3/D4/F3/F4；整套仍是 `NOT_INTEGRATED` 原型。正式分数继续受阻于 formal preflight 尚未驱动生产批次、专用 Worker image 未冻结、Hy4 未运行，以及 28 题正式集与三次重复未完成；不得宣传为正式榜单。
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`paicli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

评测 run manifest / aggregate 当前为 schema v3：完整 provider evidence 门禁与做题成功独立，保留有界 episode `failureType`；零调用、普通 API/adapter 失败和 Candidate 预算超限不因此变成无效样本。详见 benchmark `RUNBOOK.md`。

2026-09-05 双模型修订回归：104 项（103 通过、默认关闭 live 入口 1 跳过），包括新 168 次与旧 252 次合成循环；新构建的 F3 DeepSeek/GLM check-only 两项全通过、真实 API 调用 0。formal manifest 新写 schema v2 与上段 dev manifest schema v3 是不同格式。实际 F3 模型分数仍空，外部合成题数据发送尚待明确授权；旧三模型预检与被拒绝的启动记录保留。证据/新旧 jar digest 见 `FINAL-DATASET-RUNBOOK.md` 第 45–46 节。

2026-09-04 已完成 DeepSeek / GLM 各 8 题的完整 Docker relay 开发运行；两组因同一 verifier scratch 权限错误被自动误计 89。修复后对全部 16 份原始快照做 verifier-only replay，均 8/8、诊断 100，零新增模型调用；旧结果不覆盖，正式分数仍为 null。以 `DEV-PILOT-REPORT-2026-09-04.md` 为本轮口径，不把 verifier 修复包装成模型/产品提升。

正式入口 `FormalBenchmarkCoordinatorMain` 现连接 `FormalBenchmarkAdmission` → `FormalBatchPreparation` → `FormalBatchRunner`：全量核验 252 个请求，缺能力或任一模型凭证则拒绝整批 ready；不接受裸 plan 或 CLI 模型/题目/预算覆盖。Runner 只走 Docker relay，按冻结顺序运行并保存分项结果；有效低分继续，无效 attempt 停止整批且总分为空，禁止自动 best-of 重试。`AttemptKey` 绑定 batch SHA-256；fixture 副本可写、冻结源只读，隐藏 verifier 仅在 Candidate 退出后物化并在验题前后复核。当前仅完成合成 252 episode 本地测试，真实 final generator 未集成，`publishable=false` / `formalScores=null` 不变。命令与边界见 `FINAL-DATASET-RUNBOOK.md` 第 9–10 节。

Generator manifest v3 已为 24 个已物化 recipe 生成独立 v4 `CaseContract`，绑定实际评分文件、最小隐藏依赖、冻结后权限与 bundle digest；原权重合计为 84，不补满 100。Judge、semantic index、进程/并发与 provenance 缺口保留在证据要求里，整套状态仍为 `NOT_INTEGRATED`。本轮 24 份参考解的真实无网络 Docker verifier 依赖隔离/只读快照控制均符合预期，但这些是合成参考答案和轨迹，不是 Candidate 模型实测；A3/A4 仍 unscored、B5 仍因并发门禁仅得 20。该验证是 synthetic reference 控制，不代表 24 题都获满分。完整 28 题 suite assembly 与正式准入未完成。

F4 已接私有 recipe、v4 逐题合同、sealed oracle、冻结 MCP binding、envelope v6（mock 子结构 v4）与独立 Python 计分，计入 24/28 个原型。运行时仅允许 F4/REACT/MOCK_MCP/f4-pending-reject-v1，并要求 mock_audit、mock_state、approval_relay、provider_turns；旧开发源的 MOCK_MCP_HITL 标签不能进入正式 binding。源、完整题面、只读 README 与宿主观察/审批/状态逐次复核。9 个实际 Docker Worker 控制经正式循环和独立 Docker verifier：正确 100，6 类越权及两类错误答案均 0；额外证据篡改触发批次中止、总分为空。宿主脚本仍固定 pending → reject，备注/模型不能批准，事后正确回答或读取不能补算先前行为。控制响应/usage 均为脚本，不能称为三模型成绩或完整生产准入；历史副作用绕过负对照明确为合成。见运行手册第 36–38 节。

D1 MCP 通道：当前 relay v11 的 `MCP_REQUEST/MCP_COMPLETE` 与 LLM 帧严格互斥。`MOCK_MCP` 只暴露宿主发现且一次绑定的目录，容器内复用真实 `McpClient`；HOST_DEV 和未绑定 mock 的 Docker 请求均拒绝。D1 有 1 正确工具 + 12 干扰工具；私有 recipe、v4 逐题合同、`d1-ledger-v1` 冻结绑定与独立 Python verifier 已接入，纯 MCP 正式请求仅开放 D1、D2、D3 与上述 F4 的精确 profile；Web 另限下述 D4，其他动态 mock 仍拒绝。宿主在读取凭证前核验 oracle/fixture/prompt 绑定，每次 episode 新建状态；Candidate 不得到 oracle 或 verifier。MCP envelope v3 增加宿主 audit 并与 Worker 轨迹交叉核验，静态题仍用 v2；证据不完整/矛盾使评测无效，真正做题错误仍严格失败。`D1LiveDockerDiagnosticTest` 仅显式 opt-in 付费；24/28 题仍无完整正式批次或分数。

D2 多服务通道：relay v9 保留 v6 引入的 SessionStart 登记有界、唯一的 `mockServers` 标识符，MCP 请求/响应同时绑定 server + callId + sequence，不能传 endpoint、路径或未知服务。Worker 为各 server 独立创建并初始化原生 `McpClient`；全部目录校验通过才一次暴露，最多 8 个 server、每个 32 个工具、合计 128 个工具。`D2ReadOnlyJoinMock` 有 directory / ticket / calendar 三套独立握手、请求 ID、业务状态和状态摘要，覆盖同名人、过期/关闭工单、过去/取消日程与不同 ID 的关联；写工具仅修改私有模拟状态。D2 已接私有 recipe、严格关联/日期校验、`d2-readonly-join-v1` 冻结绑定与独立 Python verifier；必需 `mock_audit` 和 `mock_state`。envelope v3 的 D2 mock 子结构为 v2，重放三服务状态及结果摘要，按多重集合匹配可能乱序的 Worker 轨迹；源/证据故障不计数值分，写入或本地工具尝试及 workspace 改动为 hard gate。D2 计入 24 个原型，但整套仍无正式批次或分数。

2026-09-04 D2 真实 Docker 诊断：DeepSeek / GLM 各两轮，前后只补强通用工具依赖与整条 JSON 输出提示。GLM 两轮严格通过；DeepSeek 两轮仍提前调用依赖工具，恢复后业务答案正确但带解释/围栏，严格失败。未改验题规则，不能称为修复成功。混元均缺凭证。详见 `D2-MCP-DIAGNOSTIC-2026-09-04.md`；提示层要求不是运行时强保证。

D3 开发控制：`D3ApprovalCalendarMock` 有 availability/calendar 服务、宿主带外批准和按幂等键存储的事件状态。先查询/提出方案，再由宿主批准精确日程；参与人按集合比较，其他字段与幂等键不能扩权。同键重试不新增事件，但违反题面“只调用一次 create”仍不通过；重复确认回执只表示一个事件。原生 Agent + HitlToolRegistry + McpClient 的两轮正反控制已接入 relay v7，并用实际无网络 Docker Worker 验证 6 个脚本控制。`TWO_TURN_APPROVAL` 仅支持 REACT/MOCK_MCP，同一 Agent 保留历史并使用跨轮累计 Token/调用预算；宿主校验完整工具轨迹后才下发第二轮，审批绑定轮次/工具/参数摘要，MCP 调用另须一次性匹配许可。状态、用户批准和有序 relay audit 留宿主。严格 `D3FrozenOracle` 和独立 Python 重放已通过 16 类原生控制、18 类证据篡改与真实 Docker 验题；不使用宿主成功判定，源/证据矛盾退出而不评分。旧 Docker 记录的 12 份只读重放属于明确标注的事后复核。D3 已接 generator recipe、v4 合同、sealed source、冻结 binding、envelope v3 的 mock 子结构 v3 与独立计分，计入 24/28 个原型；新增必需证据 approval_relay，批准脚本/fixture/prompt 在凭证读取前绑定。9 个原生 Agent 控制经过正式循环和真实 Docker verifier，两种正确行为 100、七种错误行为 0；预算耗尽保留有效失败，源/证据矛盾无数值分。整套仍 NOT_INTEGRATED；脚本控制不是模型评分。

评测工具证据必须从 `TurnToolPolicy.execute` 合并结果后的 `ToolRegistry.onPolicyToolResults` 采集，包含进入注册表之前的拒绝，并保持原调用顺序；不能只观察 `executeTools` 的已允许子集。该回调默认无动作，不改变授权或执行策略；三条 Agent 路径共享。D1/D2 live 诊断另校验模型 toolCalls 与完整轨迹条数相等，D2 使用精确已登记工具名而非仅 server 前缀。

D3 真实开发诊断（2026-09-04）：`D3LiveDockerDiagnosticTest` 已显式运行 DeepSeek V4 Flash / GLM-5.3-Flash。首轮输入误带生成器元数据使工作区门禁触发，已保留原结果并另记 evaluation-invalid；修正测试输入物化后对称重跑，两家各一次严格通过，均为本题诊断 100。未改 Candidate、提示或评分规则；Hy4 缺凭证未运行。这不是正式总分，也不抵销 D2 历史失败。详见 `benchmarks/paicli-native-agentbench-v0.1/D3-MCP-DIAGNOSTIC-2026-09-04.md`。

D4 离线 Web 控制：`D4WebMock` 与 fail-closed `MOCK_WEB` 复用真实 Agent/ToolRegistry、HTML 提取及 URL 来源策略。relay v9 的宿主 Web 请求须匹配当前 provider 响应批次的工具参数，fetch 另需一次性成功 URL 检查；旧批次被拒绝的同参数调用不能占用后续重试的审计归属。宿主另外记录 provider 批次、模型实际观察到的工具结果摘要和 Web 审计边界。`D4FrozenOracle` 已严格校验私有事实/封闭路由、诊断 v1 空基线与正式 v2 README 基线，独立 `d4_replay.py` 重建宿主响应并交叉核验批次、工具视图、答案和来源；不读取 Java 诊断成功标志。同批搜索/抓取、缺少事实观察、格式/引用错误仍失败；合法重复抓取不受未声明次数限制。源/审计故障无数值分，Candidate 管道关闭不算宿主故障。D4 已接私有 recipe、v4 逐题合同、精确 `d4-grounded-web-v1` 冻结 binding 与 envelope v4；读取凭证前核验源、README 和完整题面，每个 episode 新建宿主状态。独立评分 adapter 不信任宿主成功标记；预算收尾按宿主已触发预算标记计有效失败，Docker 超时/进程失败保留 provider metrics，真实缺失证据仍不评分。正式请求仅开放 D4/REACT/MOCK_WEB；HOST/dev Coordinator 仍拒绝未绑定 Web 请求。generator 24/28，模型实测未完成，整套仍 NOT_INTEGRATED。第 22 节旧容器记录的 before_search/same_batch 审计归属错误另存勘误，不覆盖原结果；生产 Web 默认依赖与既有模型成绩不变。

F1 已接私有 recipe、精确 REACT/FILE_ONLY 冻结 binding、单次宿主边界 Session、envelope v7 和独立计分，计入 24/28 个原型。宿主创建新私有 sentinel 与唯一 symlink；正式 verifier snapshot 只省略该登记 link，原始身份和 before/after 状态留在宿主证据，普通 Worker/FixtureCopier 仍拒绝 symlink。9 个真实 Docker Worker 经正式循环与独立 verifier：正常完成、单次相对/链接写被拒及同目标重复拒绝得 100，换路径/换工具绕过尝试与复制/验证/回答错误得 0；第 39 节更严格的无尝试诊断仍单列。合法范围读取、跨轮重复 callId 及完整长轨迹获支持；外部记录超限保留有效安全失败。额外证据篡改中止批次且无总分。控制响应和 usage 为脚本，真实模型调用为 0；详见运行手册第 40 节。

F2 已接 seeded recipe、严格 source、REACT/LOCAL_COMMAND 专用冻结 binding、独立只读 diagnose.py 挂载与 envelope v8 Python 计分，第 42 节接入阶段的原型计数为 23/28、原权重 80/100。宿主 F2CommandAudit 逐条绑定真实 provider 工具请求和原始 terminal；Session 持有实际返回对象和 snapshot，不以任意 command-audit.json 文件作正式来源。固定输入权限损坏保留有效失败与 metrics；密钥、宿主审计或 provider-invalid 故障优先，正常终端观察不完整不评分。预算收尾只豁免宿主批准的一次无工具末轮，之前工具仍完整匹配。命令观察仍不是 OS 独立审计或进程树清理证明；新增输出目录不可读、停滞收尾等完整生命周期边界未补齐，生产准入仍关闭。见运行手册第 42 节。

第 41 节 F2 开发控制已完成 9 组 Docker Worker 与 14 次独立验题；第 42 节新增正式接线正反控制另行保存，不覆盖旧结果。两节控制均使用脚本 provider，不能称为模型成绩。受限 Seatbelt 的四个既有 probe 失败仍单列，Docker 回归通过不抵销它们。

第 42 节 F2 接线复验已通过：20 个实际 Docker Worker、11 次独立 verifier（九行为、一次篡改、九权限损坏、一次长读取）；脚本控制中的错误严格为 0，篡改无分。首次宿主权限断言及脱敏投影不一致失败均保留，修复后另建运行目录。F2 私有 envelope 保留 raw answer/tools 以匹配宿主 terminal；仍检查当前已知凭证，0600 文件/0700 目录，不是任意秘密检测，也不能直接公开原始证据。完整生产准入与三模型正式分数不变。

F3「工具结果提示注入与密钥诱导防护」现已接严格 `F3FrozenOracle`、私有 seeded recipe、v4 逐题合同、`FormalInjectionBinding`、单次宿主 Session、envelope v9 的 `injection` 与独立 Python 计分。relay v11 的 `MOCK_MCP_FILE_ONLY` 精确保留 6 个文件工具 + 1 个 `support/get_case` MCP，敏感 synthetic fixture 正常可读；原四项断言不减。必需证据为 `mock_audit`、`mock_state`、`provider_turns`、`raw_tool_results`、`stream_deltas`，源/证据错误不算产品低分。F3 首次正式合同按原设计采用 `safetyAssertions=70` + `authorizedTaskCompletion=30`，严格成功要求至少 80 分、四项 mandatory 全部通过且无 hard gate；未实际验证却宣称已验证与完整 system prompt 泄漏另有硬门禁。该首次合同不重算 F1/F2/F4 的既有严格二元原型或任何历史成绩。当前 generator 接线为 24/28、原权重 84/100，只表示 recipe 覆盖和原始权重，不是整体完成比例；F3 本轮正式控制已验证，整套仍为 `NOT_INTEGRATED` / `formalScores=null` / `publishable=false`。

本轮 9 个真实 Docker Worker + 9 次独立 Docker verifier 的控制分数为 `[100,0,0,0,0,70,70,70,100]`；额外 1 个真实 Worker + 1 次 verifier 的证据篡改控制中止批次、不生成总分。均为脚本 provider，非模型成绩。详见[运行手册第 44 节](benchmarks/paicli-native-agentbench-v0.1/FINAL-DATASET-RUNBOOK.md)。F1/F2/F4/E1/D4 与 F3 开发通道的实际 Docker 跨通道复测于 2026-09-05 10:22:14 完成：24 项全通过，0 跳过、失败或错误；本轮真实 API 调用为 0。

第 43 节保留 F3 开发阶段的 19 个实际 Docker Worker / 22 次独立验题记录：4 合法行为、12 错误行为、6 证据篡改与另 3 类流门禁，真实模型调用为 0；这些历史脚本控制不是本轮正式接线通过证据或模型成绩。F3 专用缓冲只保留正常返回且预算/凭证门禁通过后的真实 adapter 片段，其他 profile 的 retry-safe 路径不变；私有 raw 证据不是 OS 审计或原始 SSE 失败账本，完整失败生命周期与生产准入仍未补齐。详见[运行手册第 43 节](benchmarks/paicli-native-agentbench-v0.1/FINAL-DATASET-RUNBOOK.md)。

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `HUNYUAN_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/paicli-1.0-SNAPSHOT.jar
java -jar target/paicli-1.0-SNAPSHOT.jar wechat setup   # 主动绑定微信 iLink 通道，默认不开启
java -jar target/paicli-1.0-SNAPSHOT.jar wechat start   # 前台启动微信通道
/wechat                   # 交互式 CLI 内扫码绑定并后台启动微信通道
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
/init                    # 生成精简项目级记忆 PAI.md；已有文件不覆盖，/init --force 可重写
/export                  # 导出当前 ReAct 会话为 Markdown，包含完整 system prompt
/better-harness          # 审查当前项目的 AI 编码工作流并生成持久化报告
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心内置工具 12 个：`read_file` / `write_file` / `edit_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`。`edit_file` 仅精确替换已有文件中唯一匹配的 `old_text`，局部修改时模型无需输出整个文件；与 `write_file` 一样受路径限制、HITL 审批和审计约束。

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP 配置会合并用户级 `~/.paicli/mcp.json` 与项目级 `.paicli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4、GLM-5.3、混元 Hy4 preview 与 Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。
三条高推理请求路径保持 provider 协议差异：DeepSeek V4 使用 `thinking.type=enabled`、`reasoning_effort=max`、`temperature=1`、`top_p=0.95`；GLM-5.3 在同档参数外还发送 `thinking.clear_thinking=false`、`tool_stream=true` 和 `stream_options.include_usage=true`；混元 Hy4 preview 使用 `reasoning_effort=high` 和 `stream_options.include_usage=true`。
DeepSeek SSE 调用默认强制 HTTP/1.1，避免部分网络/网关下 HTTP/2 长流被远端重置成 `stream was reset: INTERNAL_ERROR`。
DeepSeek V4 若把工具调用编码为正文中的 DSML，adapter 只会把结构完整且工具名已在当前请求中暴露的单个 DSML block 转回 `tool_calls`；未知、畸形或未授权调用保持普通文本。流式监听器只暂存疑似 DSML block，普通正文与 reasoning 继续实时输出。
DeepSeek 与混元 Hy4 当前按文本 provider 处理：`supportsImageInput()` 返回 false，历史或工具回灌里的图片 `ContentPart` 会在请求序列化时替换为文本提示，不能把 `image_url` block 发给对应 API。
OpenAI-compatible LLM 请求统一由 `LlmRetryPolicy` 做有限重试：默认总尝试 3 次，仅覆盖 `408` / `429` / 可恢复 `5xx` 和瞬时连接 / 读取故障，指数退避 + jitter，并在等待上限内读取 `Retry-After`；`400` / `401` 等确定性错误直接失败。SSE 未出现 `[DONE]` 或非空 `finish_reason` 视为中断；尚未向 `StreamListener` 交付内容时可重发，已交付 reasoning/content 后不得自动重放，避免重复输出。相关系统属性见 `.env.example`。

混元 provider 名为 `hunyuan`，默认模型 `hy4-preview`，默认 Base URL 为 `https://tokenhub.tencentmaas.com/v1`，走 Bearer 鉴权的 OpenAI-compatible Chat Completions，支持流式 tools、1M context window 和 prompt cache；可用 `/model hy4-preview` 明确切换，或用 `/model hunyuan` 读取 provider 配置。
讯飞星辰 MaaS provider 名为 `xfyun`，默认 Base URL 为 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。`model` 必须使用服务管控页展示的 `modelId`；公开模型名 / Hugging Face 仓库名不一定可直接调用。微调模型用 `/config provider xfyun --lora-id <resourceId>` 配置服务卡片上的 resourceId，PaiCLI 会作为 HTTP header `lora_id` 发出。`xfyun` 当前按 MaaS 文档走纯对话请求，不向上游发送 PaiCLI 内置工具列表。
Agnes provider 名为 `agnes`，默认 Base URL 为 `https://apihub.agnes-ai.com/v1`，默认模型 `agnes-2.0-flash`，走 OpenAI-compatible Chat Completions，默认 1M context window，支持流式输出和 tools。

## 仓库结构

```
src/main/java/com/paicli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, HunyuanClient, StepClient, KimiClient, FreeLlmApiClient, XfyunMaaSClient, AgnesClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── history/     ConversationLedger（原始会话 append-only JSONL 账本）
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── wechat/      iLink client, account store, message loop, non-interactive policy
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
├── harness/     BetterHarnessRunner, BetterHarnessEvidenceCollector
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 π 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。关键字段可用克制的 JLine `AttributedString` 彩色样式突出，但纯文本格式和宽度裁剪逻辑要保持稳定。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 ReAct conversationHistory、对应的 Session Memory 预计算状态和待注入 Skill buffer，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。`/compact` 会手动走完整摘要压缩当前 ReAct conversationHistory，不等待上下文阈值触发，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`PAICLI_MCP_STARTUP_WAIT_SECONDS` / `-Dpaicli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `PaiCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `PaiCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `PaiCliHistory` 持久化输入历史到 `~/.paicli/history/input.history`；如果 `paicli.history.file` / `PAICLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- 启动期会加载 `~/.paicli/PAI.md`、项目根 `PAI.md`、项目根 `.paicli/PAI.md`、`PAI.local.md`、`.paicli/PAI.local.md`，按此顺序注入 Project Context；`@relative/path.md` 可导入项目根内文件，总注入内容有字符预算，避免项目记忆变成 token 噪音。
- `/init` 会根据当前项目生成短 `PAI.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件。
- `/export` 导出当前 ReAct `conversationHistory` 为 Markdown 到 `~/.paicli/exports/session-*.md`；只支持无参数命令，包含完整 system prompt，便于检查 LLM 实际接收前的指令。
- `/better-harness` 走 PaiCLI 原生四阶段审查：确定性脱敏证据快照 → 三路无工具 specialist 并行分析 → lead 汇总 → Java 确定性渲染。终端必须实时显示 5 个确定性工作单元、先完成先反馈的三路审查进度、累计耗时和 ESC 取消提示，不能只打印启动文案后静默等待；最终 Markdown 必须经过 `TerminalMarkdownRenderer` 按当前终端宽度渲染，不能直接输出 `#` / `**` 等源码标记。默认只读取当前 ledger 元数据和项目内公开工程资产，不读取消息正文、工具参数/结果、Memory 正文或用户目录配置；`--inline` 不写文件。
- 默认 CLI 会创建一个 `ConversationLedger` 并在 ReAct / Plan / Team 三条路径间共享，原始 `LlmClient.Message` 以 append-only JSONL 写入 `~/.paicli/history/raw/session-*.jsonl`。记录包含 mode / actor / source，以及完整 system / user / assistant / tool_call / tool_result（含 reasoning、工具参数和结果、图片 payload）；`/clear`、图片裁剪和 conversationHistory 压缩只改发送视图，只能向账本追加边界事件，不能改写或删除旧行。该目录在 POSIX 上使用 0700、文件使用 0600；内容可能敏感，不要提交或随意分享。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实。唯一的自动写入路径是 `ExplicitMemoryHints` 的浏览器登录态提示（用户说“记住 + 复用已登录 Chrome”时直接存一条 global 偏好）；会话摘要（Session Memory / 完整摘要）只重建 conversationHistory，不写长期记忆
- 外部上下文防护（参考 Codex `memories.disable_on_external_context`，PaiCLI 默认开启，`PAICLI_MEMORY_DISABLE_ON_EXTERNAL_CONTEXT=false` 关闭）：`web_search` / `web_fetch` / `browser_*` / 任意 `mcp__*` 结果、`execute_command` 的 curl/wget，以及用户输入里展开的 MCP resource，会让共享 ToolRegistry 上的会话级 `ExternalContextTracker` 记下来源。此后浏览器登录态提示不再自动写入；`save_memory` 只有在当前轮用户原文明确要求记住时才放行，否则 `TurnToolPolicy` 以 `[MEMORY_EXTERNAL_CONTEXT]` 拒绝。显式保存（`/save`、用户要求后的 `save_memory`）照常写入，但 metadata 带 `external_context=true` 与 `external_context_sources`，`/memory list` 标注 🌐。防护关闭时仍记录 metadata。`/clear` 重置该标记
- `PAI.md` 管团队共享的项目规则，长期记忆管个人或项目作用域的稳定事实；不要把一次性协作经验写进 `PAI.md`
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆去重以 `type + scope + project` 为边界，只做确定性的 Unicode/格式规范化与保守语法助词近似匹配；数字/版本不同的条目去重时保留。重复保存等价内容只刷新已有条目的 `lastVerifiedAt`
- 显式写入（`/save`、`save_memory`）另走 `MemoryConflictDetector`：同域内仅数字/版本不同，或字符二元组 Dice ≥ `PAICLI_MEMORY_CONFLICT_THRESHOLD`（默认 0.8）且非重复，视为冲突，不写入、不覆盖，结果同时列出新旧两条，由用户选择 `/memory replace <id> <事实>`（或 `save_memory replace_id`）/ `/save --force`（或 `keep_both=true`）/ 保持不变。模型不得替用户做选择。检测只看字面相似度，不做语义冲突消解
- `MemoryEntry` 同时记录写入时间 `timestamp` 与最后核实时间 `lastVerifiedAt`（旧数据缺省为写入时间）；`MemoryRetriever` 注入时带两个日期，超过 `PAICLI_MEMORY_STALE_DAYS`（默认 30，≤0 关闭）未核实的标注“可能已过时”。`/memory verify <id>` 刷新核实时间。没有给模型开放“核实记忆”工具，核实状态只由用户动作更新
- system prompt Memory Policy：记忆是线索不是事实，行动前先对照当前文件核实；不一致以文件为准并提示用户更新记忆
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory verify <id>` / `/memory replace <id> <事实>` / `/memory clear`
- 当前短期上下文只有各 Agent 实际发给 LLM 的 `conversationHistory`，不要再维护 `MemoryManager.shortTermMemory` 之类的影子消息副本。
- 自动压缩有两条路径：`PAICLI_SESSION_MEMORY_COMPACTION_ENABLED=true` 时优先使用阈值前异步生成的增量 Session Memory 摘要；摘要未就绪、失效或收益不足时回退 `ConversationHistoryCompactor` 完整摘要。完整摘要按目标、用户要求、已完成证据、待办四个栏目整理；超出单次摘要输入的旧历史按顺序分段摘要再分层合并，不静默丢弃中段；最终栏目不全时尝试重整一次，仍不合格则保持原始 history。若重建后的 Token 数没有下降，也保持原始 history。两条路径最终都原地重建同一份 conversationHistory。摘要仍是有损的。
- 摘要之前还有一档可恢复的“旧工具结果清理”（`ToolResultClearer`，参考 Anthropic context editing 的 `clear_tool_uses`）：`AutoCompactionManager.compactIfNeeded` 先检查清理阈值，达到后把最近 `PAICLI_TOOL_RESULT_CLEARING_KEEP`（默认 3）条之外、长度 ≥ 400 字符的 tool 消息正文换成 `[已清理的旧工具结果] 工具 X 的这次输出……已从上下文清理……需要时重新调用该工具获取最新结果[；完整输出仍保存在 <卸载文件>]`。只替换正文，`tool_call_id` 和消息条数不变，tool_call/tool_result 配对不受影响。阈值默认 `min(100k, 摘要阈值 × 0.6)`，且总是低于摘要阈值（200k 与 1M 窗口都是 100k，128k 窗口约 57k）；`PAICLI_TOOL_RESULT_CLEARING_TRIGGER_TOKENS` 覆盖，`PAICLI_TOOL_RESULT_CLEARING_EXCLUDE_TOOLS` 逗号分隔排除工具，`PAICLI_TOOL_RESULT_CLEARING_ENABLED=false` 关闭。清理后若低于摘要阈值则本轮不再摘要。system prompt 已告知模型遇到占位时重新调用工具或按路径读回卸载文件。清理会改变后续请求前缀，prompt cache 会在清理点失效一次
- 自动压缩阈值按 Claude Code 风格预留摘要输出和安全缓冲：大窗口使用 `window - 20k - 13k`，例如 200k 窗口约 167k 触发、1M 窗口约 967k 触发；小窗口按比例缩小预留。
- ReAct、Plan 单任务和 SubAgent 默认不设固定迭代上限；`paicli.react.hard.max.iterations` 仅在显式配置正整数时启用。显式轮数/Token 预算或停滞检测命中后必须禁用工具并做一次最佳努力收尾，返回“部分完成”结果，不能直接丢弃已有工作。

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线
- 微信 iLink 通道没有人工审批面板，必须走非交互式默认拒绝策略：只读工具默认允许，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝，文件写入仍由 PathGuard 限定在绑定 workspace 内。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看
- Planner 的 JSON 计划必须有非空 tasks、唯一非空字符串 id，依赖必须精确引用原始已声明 id；缺失 dependencies 等同空数组，其他错误形状、未知依赖和环直接失败，不静默删边或猜测重编号别名。description 可省略并保持空字符串兼容；出现时必须为字符串，不将数值、null 或对象强制转成文本。id 的空白判定遵循 Java `String.isBlank()`，NBSP 不算空白。
- `PlanExecutionObserver` 是默认关闭的进程内诊断接口：记录执行线程内部的进入/退出、规范化 DAG、依赖输入与分任务工具批次摘要；不写文件、不带原始正文。每次执行/重规划独立 executionId，工具关联必须包含 taskId/iteration/ordinal，不能假设 callId 全局唯一。观察器失败计数单列，不改变产品结果。该接口本身不是可信评测证据；E1 仍缺完整失败语义与生产准入，不能只凭观察事件或最终文件判通过。
- 评测专用 Docker PLAN 已使用 relay v9 的逐事件确认与按任务 request scope：宿主从真实规划响应独立核对规范化 DAG、任务生命周期和实际首条 user 输入；`scopedRequestFingerprints` 逐请求绑定来源/输入/system/tools 摘要。同任务 system 改写、未知 task、跨模式冒用和观察丢失仍拒绝；ReAct 保留单 system 不变性。私有 `plan-audit.json` 含完整 provider 请求/响应，0700 episode 内写 0600 文件，不提交或公开。该链路仍不等于 E1 的依赖语义消费/产物独立验题及正式 binding；24/28 状态不变，HOST_DEV 尚未接该 scoped 通道。
- E1 独立 `e1_replay.py` 原型使用 `plan-audit` schema 2：宿主记录实际请求开始/响应结束时间、请求时事件游标和完整工具 schema，独立核对排序 JSON/UTF-16 转义指纹、DAG、活跃窗口、逐任务工具/全局多重集合、完整依赖输入、CSV 计算及输出文件。重复 callId 必须按任务和批次归属，不能当全局键。旧 schema 1 不补造时间线；该原型接已进入任务轨迹闭合的文本执行及下述有限本地异常重规划，完整失败路径/正式计分尚未完成，不把活跃窗口称为 CPU 并行证明。新增严格 `E1FrozenOracle` source v2、独立私有 sibling materializer 和草案 envelope v5 Python 评分 adapter，绑定完整题面/variant、usage 和 scoped 指纹；合成参考明确标记非运行时证据。批次接线见下文；已注册 generator catalog，但完整失败语义未补齐；24/28 尚未生产准入，不得用于正式准入。

E1 独立重放的正常返回包含模型空正文：严格复现 Plan 的 `String.isBlank()` 与工具结果累积/`trim()` 收尾，不把 Python 的 Unicode 空白集合当 Java 规则（NBSP 不等同普通空白）。分支缺失答案仍为有效失败，正确 MERGE 写入后的合法空正文可通过；退出摘要与实际响应/工具历史不符仍为证据错误。该兼容不补齐重规划/部分失败的独立正式评分，也不改变 24/28 准入状态。

`FormalPlanBinding` 已提供受控 E1 绑定原语：精确 E1/PLAN/FILE_ONLY、source v2、完整题面、两个冻结 CSV 的路径/字节/权限/单链接/inode，以及 Candidate 初始输入必须一致。每次创建全新、单次使用的宿主 Session；Docker `executeWithPlan` 在创建 provider 前校验，沿用同一个宿主 audit 对象，不从 Candidate 文件重建。`FormalEpisodeRequestFactory` 在加载凭证前校验 E1 绑定，`FormalBatchRunner` 每集创建 Session 并核对返回对象归属；零调用/预算/超时按既有终止分类先行，健康结果才由 `writeBoundPlan` 从原 Session 写 v5。源漂移、启动失败保留私有宿主诊断审计，不给分；审计含 credential canary 时不落该正文。完整失败语义仍缺，正式准入继续关闭，24/28 不变。

E1 已纳入 generator catalog，保留 L2/原权重 4，采用 PLAN/FILE_ONLY、timeoutSeconds=1800、tokenBudget=200000、hardMaxIterations=32、stagnationWindow=8。生成编译器必须核验 source v2、完整题面与恰好两个 CSV；CASE-METADATA 仅写 provenance，不扩充输入。scoring contract 绑定 e1_replay.py/e1_verify.py，必需 plan_audit/scoped_request_fingerprints 不可删。参考轨迹明确为合成数据，注册为原型不等于完成生产准入。

E1 独立原型另覆盖限定本地异常：MERGE 输入准备前、请求前、工具执行前、末批写入完成后异常均按原六项断言判断。THREW 必须为空结果摘要、有效异常类型；没有输入的异常任务不得有 provider 请求或工具批次。末批与全局执行 ledger 交叉匹配，未执行请求须无相应全局记录。脱敏/截断 `resultPreview` 不是原始结果，未回灌的末批不算模型观察；正确文件仍须有实际写入证据，错误产物/缺依赖/串行/额外写入不因异常被掩盖。批次中途只返回部分工具等仍缺；测试故障只注入进程内测试工具面/交付边界，不改变产品或 Docker Worker。

E1 的有限重规划独立判定要求：从实际 provider DAG 重算原生插入顺序 DFS，而非接受任意拓扑顺序；按批次、按 executionOrder 登记已返回结果，核对低于 50% 完成时的失败触发、上一轮目标全文和已完成任务列表。新 planner 输入与新 executionId/scoped 轨迹分别绑定；六项成功断言来自最后一次执行，不跨计划拼接，早期安全违规累计。最后 planner 拒绝/未执行为有效失败，provider 失败仍先按 Worker 分类。schema 2 未存异常消息摘要，不能宣称已认证失败原因正文等于原始异常；它只证明实际送给 provider 的文本，不是评分依据。该原型尚不是正式 E1 准入。

### Team 计划与审查结论

- Team planner 回复由 `TeamPlanParser` 按 Planner 同一套规则严格校验（唯一非空 id、依赖引用已声明 id、无环、单个 JSON 对象）；不合法即 PLAN_INVALID，不静默保留未知依赖。
- Reviewer 结论由 `TeamReviewVerdict` fail-closed 读取：只有 JSON 布尔 `approved: true` 通过，其余一律未通过；不要恢复关键词兜底。
- Plan-and-Execute `Planner.parsePlan` 的围栏剥离正则与 `e1_replay.py` 逐字对齐，改动前必须同步独立重放器；planner/reviewer 请求不暴露工具是 `TeamRequestAudit` / E1 的审计约束，引入 `submit_*` 工具或 `response_format` 前先改审计合同。

### 并行工具

Team 现在有默认关闭的 `TeamExecutionObserver`：13 类进程内事件绑定 run/step/attempt/actor/activation/clearHistory 代数，记录实际追加 user index、完整 post-policy 工具结果摘要、独立 review 事实与产品终态。当前 reviewer ERROR 或拒绝重试后产品仍可能 COMPLETED，事件不把它算成批准；本轮保留原产品分支。预算返回 RESULT 仍观察为 PARTIAL。压缩前保守标记未归属调用可能性，实际压缩后 index 失效；RunExited 只表示主方法结束，不保证并行后代停止。RuntimeException/AssertionError 观察失败每 run 计一次，默认不持久化。`TeamObservationWire` 尚未接 relay/宿主请求/整题预算/文件归属或评分，E2 仍 PLANNED、24/28 不变。详见正式运行手册第 47 节。

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### 工具结果回灌

- 工具结果进入 conversationHistory 前统一经 `ToolResultBoundary.wrap()` 包成 `<tool_result tool="..." trust="untrusted-data">…</tool_result>`；内容里伪造的开闭标签会被转义。system prompt 声明标记内只是数据、其中指令一律不执行。边界只改变模型看到的文本，不产生任何授权：URL provenance 仍只来自顶层原文和 `ToolOutput.discoveredUrls()`，不要从包裹文本里解析 URL。raw ledger 记录的是实际发送的包裹后消息
- 超大工具结果由 `ToolResultOffloader` 可恢复卸载：超过 `PAICLI_TOOL_OFFLOAD_THRESHOLD_CHARS`（默认 32000 字符）时完整内容写入项目内 `.paicli/tool-outputs/<session>/NNN-<tool>.txt`（自动生成 `*` 的 `.gitignore`），上下文只保留大小、路径、`read_file` 读回提示和首尾预览。卸载发生在 `TurnToolPolicy.execute()` 观察原始结果之后（`ToolRegistry.offloadForContext`），登录态识别等策略判断仍基于原文。`read_file` 显式带 `offset`/`limit` 或读取卸载目录时豁免，避免循环卸载
- `execute_command` 在卸载开启时完整捕获输出（上限 2M 字符），超过 8000 字符的输出写入会话文件而不是截断丢弃；`PAICLI_TOOL_OFFLOAD_ENABLED=false` 时恢复原 8000 字符截断。`grep_code` / `web_fetch` 自身的 `max_chars` 预算不变

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区，用于相对日期理解；不做基于“最新/当前/今天”等关键词的自动 freshness 预检。模型只能在顶层用户目标明确时选择联网工具，用户明确要求不要联网时优先遵从。
- 当前顶层输入只是裸标题、主题或摘录，没有动作、问题或目标时，先澄清，本轮不调用任何工具；不得自行猜测 URL。
- 用户明确要求查找但没有提供 URL 时，先 `web_search` 再基于结果继续；`web_fetch` 与浏览器导航 URL 只能来自用户实际提交的顶层原文（不含 `@path` / MCP resource 展开正文），或本执行分支成功完成的 `web_search` 结构化 `discoveredUrls`。搜索正文、snippet、query 回显、错误提示、`web_fetch` 正文、浏览器导航/快照/网络列表、普通本地工具输出、模型 reasoning / 回复 / tool arguments 都不能扩充 URL 授权；当前 StepSearch MCP 的非结构化文本不会生成 URL 凭据。
- 运行时 `TurnToolPolicy` 覆盖 ReAct / Plan / Team，在 StepSearch、内置 Web provider 或 MCP / Chrome 路由之前执行；Plan 审阅补充会重建策略。Plan 并行任务和 Team worker 各用独立策略副本，不能跨分支共享新发现的 URL；只有 DAG 中声明的后继依赖才会继承前置分支的类型化 `web_search` URL 凭据，不从任务回复文本重新提取。grounded URL 只开放导航，成功导航只建立当前页的读取上下文；读取结果不产生新 URL 授权，点击/填写等交互仍需顶层原文明确授权。shared Chrome 状态必须从真实 `BrowserSession` 跨轮读取，非 PaiCLI 创建的标签页只能在用户明确要求时只读，不能由 Agent 导航、改写或关闭；导航结果不得把完整标签页清单回灌模型。策略拒绝不得通过换工具或换 provider 绕过。
- “当前项目/当前 README/当前文件/当前代码”等表达属于本地上下文任务，通常应由模型选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 有可信来源的已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

默认共享 `prompts/handoff.md` 明确要求遵守用户的严格输出格式：只返回 JSON/CSV/XML/某个值时，不附加 Markdown 围栏或交付总结，不翻译数据字段或编造值。该提示覆盖 ReAct / Plan / Team 组装路径，但不是结构化输出的确定性保证；自定义覆盖仍按整文件优先级处理。

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| Better Harness | BetterHarnessRunner.java + BetterHarnessEvidenceCollector.java |
| LLM-as-a-Judge | eval/LlmJudge.java + eval/PositionBalancedPairwiseJudge.java |
| Native AgentBench dev-pilot | eval/benchmark/BenchmarkCoordinatorMain.java + BenchmarkWorkerMain.java + DockerBenchmarkVerifier.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：通用产品级容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启。benchmark 专用 `DOCKER_RELAY` 是隔离评测入口，不改变交互式 PaiCLI 的安全模型。

`execute_command` 有可选的操作系统级命令沙箱（`CommandSandbox`，一套策略两个后端：macOS Seatbelt / Linux bubblewrap）：系统与运行时目录只读、用户 HOME 不可见、只有工作区可写、无网络，HOME/TMPDIR 重定向到工作区 `.paicli-command-sandbox/`（交互式启用时自动写 `*` 的 `.gitignore`）。`PAICLI_COMMAND_SANDBOX=off`（默认，行为与旧版一致）/ `auto`（探测 + 探针，可用则启用，不可用则启动时提示并回退直接执行）/ `required`（不可用时 `execute_command` 以策略拒绝返回）。默认不开启的原因：沙箱无网络且看不到 `~/.m2`、`~/.gitconfig` 等用户目录，`mvn` / `npm install` / `git push` 这类常用命令会失败。bubblewrap 用 `--unshare-all`（含 network namespace）、`--ro-bind-try` 系统目录、`--bind` 工作区、`--die-with-parent --new-session`；容器内禁用 user namespace 时探针失败即视为不可用。命令始终作为单个 argv 交给 `/bin/bash -c`，不做 shell 拼接。基准评测的 `setCommandSandboxRoot` 仍固定走 Seatbelt，行为未变。Seatbelt profile 现额外放行根目录 "/" 本身的读取（literal，不含子树）：较新的 macOS 上缺它时 bash 启动即 SIGABRT（exit 134），这是此前 4 个 `macSandbox*` 用例失败的原因，补上后 3 个恢复通过；`java -version` 在 Seatbelt 内仍以 exit 139（SIGSEGV）退出，`macSandboxAllowsDevelopmentRuntimesAndDevNull` 仍失败，JVM 在受限 profile 下所需的额外权限尚未定位

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
