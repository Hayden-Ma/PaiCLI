# PaiCLI Smoke Bench v0.1

可执行的最小评测 suite：5 个 case，覆盖软件工程修复（2）、代码定位（1）、工具编排（1）、
安全约束（1）。每个 case 的权重之和为 100。

## 运行

```bash
mvn clean package -DskipTests
java -jar target/paicli-1.0-SNAPSHOT.jar bench \
  --suite benchmarks/paicli-smoke-v0.1/suite.json \
  --runs 1 --out benchmarks/results
```

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

## 当前边界

- v0.1 仅支持 `mode: react`（PLAN / TEAM 在 suite 中声明后会被记为 INFRA_ERROR 而非得分）；
- 5 个 case 的绝对分数样本量小，只用于趋势与防退化判断，不用于模型排名；
- `AgentBudget` 兜底参数默认：40 轮硬上限 / 40 万 token，可用 CLI flag 调整。
