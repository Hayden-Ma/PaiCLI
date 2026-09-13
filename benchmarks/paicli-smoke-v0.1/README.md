# PaiCLI Smoke Bench v0.1

可执行评测 suite：28 个 case（与 benchmarks/paicli-native-agentbench-v0.1/suite-blueprint.json 的分类口径对齐），覆盖软件工程修复（6）、代码理解（4）、工具编排（5）、终端闭环（3）、长程多步骤（4）、安全控制（4）、推理（2）。权重之和为 100：L1×7（21 分）/ L2×14（56 分）/ L3×7（23 分）。

## 运行

```bash
mvn clean package -DskipTests
java -jar target/paicli-1.0-SNAPSHOT.jar bench \
  --suite benchmarks/paicli-smoke-v0.1/suite.json \
  --runs 1 --out benchmarks/results \
  --allow-commands "javac *,java *,copy *,cp *,find *,findstr *,wc *,python *,cat *,mkdir *,dir *,ls *"
```

`--allow-commands` 为终端闭环类 case 放行命令白名单（逗号分隔；`*` 结尾按前缀匹配，其余精确匹配）。不传时 `execute_command` 全部拒绝，终端类 case 的轨迹规则会判失败。

退出码：`0` 全部 case pass@k 通过；`1` 存在失败 case；`2` 运行错误（suite 无效、无 API Key）。

报告指标：pass@1（首采样通过率）/ pass@k（k 次内至少一次通过）/ **pass^k**（k 次全部
通过，可靠性下限；与 pass@k 的差距即稳定性带宽）/ 加权得分，另附每个 case 的平均 LLM
轮数、平均工具调用数与总耗时（效率列）。

每夜定时执行可直接用 `scripts/nightly-bench.sh`（可用 `BENCH_RUNS` / `BENCH_OUT` 调参）。

## 结构

```
suite.json       suite 清单（SuiteDefinition 严格校验）
fixtures/        被评 agent 可见的工作区模板（每次采样物化到临时目录）
hidden/          verifier 资产，绝不进入 agent 工作区：
                 - Check.java   判定器（cwd=suite 目录，{workspace} 替换为临时工作区绝对路径）
                 - HiddenTest.java 隐藏测试（仅软件工程 case）
                 - trajectory.json 轨迹规则（叠加层：hidden/{caseId}/trajectory.json 存在即
                   在主判定之外追加轨迹校验，pass = 主判定 AND 轨迹判定；也支持
                   verifierType=trajectory 单独使用。支持 mustCallInOrder / mustCallAny /
                   mustNotCall / maxLlmCalls 四类确定性规则。首个实例：tool-todo-count）
results/         （运行产物，gitignore）每次 run 一个目录：manifest / summary.json /
                 report.md / cases/<id>/models/<model>/repeat-NNN/{run,verifier}.json + answer.md + raw.jsonl
```

## 防作弊设计

- 隐藏测试源码放在 `hidden/`（suite 目录内、工作区外），被评 agent 的 PathGuard 根目录
  是临时工作区，读不到也改不了隐藏测试；
- agent 对 `execute_command` 默认拒绝（bench v1 未配置命令白名单），联网 / 浏览器 / MCP
  工具默认拒绝，保证评测可离线复现；
- 每次采样使用全新的临时长期记忆库，不读取也不污染用户 `~/.paicli` 数据；
- 安全约束 case 的 verifier 会对比 `hidden/` 里的基线副本，检测受保护文件是否被改动。

## Case 清单

| id | 类别 | 级别 | 判定方式 |
|----|------|------|---------|
| se-fix-calculator | software_engineering | L1 | 隐藏测试 |
| se-fix-stringutil | software_engineering | L1 | 隐藏测试 |
| se-add-method | software_engineering | L1 | 隐藏测试（缺方法=编译失败） |
| se-fix-multi-file | software_engineering | L2 | 隐藏测试（跨文件） |
| se-fix-api-signature | software_engineering | L2 | 隐藏测试（签名不改=编译失败） |
| code-find-answer | code_understanding | L1 | 答案文件 |
| code-find-orphan | code_understanding | L1 | 答案文件 |
| code-trace-callchain | code_understanding | L2 | 答案文件 |
| tool-todo-count | tool_orchestration | L2 | 答案文件 + 轨迹叠加 |
| tool-csv-report | tool_orchestration | L2 | 答案文件 |
| tool-batch-transform | tool_orchestration | L2 | 多产物文件校验 |
| tool-log-triage | tool_orchestration | L2 | 答案文件 + 轨迹叠加 |
| safety-config | safety | L2 | 内容校验 + 禁区基线比对 |
| se-fix-null-handling | software_engineering | L2 | 隐藏测试（边界规格）+ 轨迹叠加 |
| code-find-bug-location | code_understanding | L2 | 答案文件（文件:行号） |
| tool-multi-source-merge | tool_orchestration | L2 | 答案文件（跨文件汇总） |
| term-log-line-count | terminal_execution | L2 | 答案文件 + 轨迹（必须用命令） |
| term-backup-file | terminal_execution | L2 | 基线比对 + 轨迹（必须用命令） |
| term-compile-run | terminal_execution | L3 | 答案 + 编译产物 + 轨迹 |
| safety-stale-config | safety | L2 | 答案文件 + 轨迹（禁联网） |
| safety-path-escape | safety | L3 | 工作区内产物 + 越权文件检测 |
| safety-prompt-injection | safety | L3 | 任务完成 + 泄露检测 + 基线比对 |
| l3-multi-step | long_horizon | L3 | 隐藏测试（三步联动）+ 轨迹叠加 |
| l3-debug-from-logs | long_horizon | L3 | 隐藏测试（修崩溃）+ 修复标记 + 轨迹 |
| l3-implement-from-spec | long_horizon | L3 | 隐藏测试（规格+边界，缺类=编译失败）+ 轨迹 |
| l3-stateful-counter | long_horizon | L3 | 隐藏测试（有状态边界语义）+ 轨迹 |

## 当前边界

- v0.1 仅支持 `mode: react`（PLAN / TEAM 在 suite 中声明后会被记为 INFRA_ERROR 而非得分）；
- 绝对分数样本量仍有限，主要用于趋势与防退化判断；跨模型排名需同 harness 多次采样；
- `AgentBudget` 兜底参数默认：40 轮硬上限 / 40 万 token，可用 CLI flag 调整。
