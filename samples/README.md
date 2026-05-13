# 评测黄金集

17 条标注 SQL case,专注 MySQL 深分页场景,作为 Agent 端到端评测的长期基准。

## 适用范围

Agent 的输入约定:**一定是上游慢 SQL 日志过滤出来的语句**,不会出现"其实不慢"的输入。
Agent 只产出 **3 种合法 outcome**:

| outcome | 含义 |
|---|---|
| `rewritten_deferred_join` | 改写为延迟关联(子查询取 PK + 外层 JOIN 反查) |
| `rewritten_cursor` | 改写为游标分页(WHERE pk > last_id), 需业务可改 API |
| `unsupported` | 超出深分页 SQL 改写范围, 把可执行建议(索引 DDL / OLAP 等)写到 `additional_suggestions` |

**加索引不是独立 outcome** — 加索引是 schema 改动而非 SQL 改写, agent 只能给 DDL 建议, 归 `unsupported`。

## 数据集分布

| 期望 outcome | 数量 | case ID |
|---|---|---|
| `rewritten_deferred_join` | 5 | `dj_001` ~ `dj_005` |
| `rewritten_cursor` | 4 | `cur_001` ~ `cur_004` |
| `unsupported` | 8 | `idx_001-003` (缺索引) + `oos_001-005` (越界) |
| **合计** | **17** | |

`unsupported` 占比 47% — 这反映 agent 最重要的能力是"知道自己能干什么、不能干什么", 而不是硬上方案。

## 文件结构

```
samples/
├── README.md
├── schema.sql      # 8 张测试表 schema (电商场景)
├── seed.sql        # 灌入百万级数据 (@scale 可调)
└── golden_set.json # 17 条标注 case
```

## Schema 设计

8 张表覆盖电商核心场景。每张表都有 PRIMARY KEY (`id BIGINT AUTO_INCREMENT`), 支持基于主键的等价性验证。索引有意留缺口(如 `users.city` 无索引、`reviews.rating` 无索引), 用于触发 agent 的"缺索引 → unsupported + DDL" 路径。

| 表 | 默认 @scale=5000 行数 | 备注 |
|---|---|---|
| `users` | 5,000 | 用户表 |
| `merchants` | 100 | 商家表 |
| `categories` | 100 | 分类(小表) |
| `products` | 10,000 | 商品 |
| `orders` | 10,000 | 深分页主战场 |
| `order_items` | 20,000 | 订单明细 |
| `reviews` | 2,500 | 评论(含 TEXT) |
| `user_actions` | 15,000 | 用户行为 |

百万级演示用 `@scale=1000000` (~12.5M rows), 详见 `seed.sql`。

## Case 结构

```json
{
  "id": "case_dp_dj_001",
  "complexity": "simple",
  "tags": ["deep_pagination", "single_table", "pk_ordered"],
  "input": {
    "sql": "SELECT id, user_id, amount, create_time FROM orders ORDER BY id LIMIT 500000, 20",
    "schema_required": ["orders"],
    "data_volume_hint": "...",
    "requirement": "运营后台数据导出页, 使用传统翻页 URL, 不能改前端 API 接口语义"
  },
  "expected": {
    "expected_outcome": "rewritten_deferred_join",
    "acceptable_outcomes": ["rewritten_deferred_join"],
    "min_cost_reduction_percent": 80,
    "must_pass_verification": true,
    "notes_for_evaluator": "..."
  },
  "notes": "case 设计意图"
}
```

**Agent 输入只有 `input.sql` + `input.requirement` (自然语言业务说明)**, 由 agent 自己从文字里抽取语义(API 是否可改 / 翻页模式), 不喂结构化字段。

## 评测指标

### 业务价值

| 指标 | 目标 |
|---|---|
| `high_confidence_rate` | > 70% |
| `p95_latency_ms` | < 120 000 |

### 改写效果

| 指标 | 目标 |
|---|---|
| `outcome_match_rate` | > 85% |
| `verification_pass_rate` | > 85% |
| `cost_reduction_median` | > 70% |
| `business_context_compliance` | 100% |
| `assumptions_explicit_rate` | > 90% |

### Agent 行为

| 指标 | 目标 |
|---|---|
| `avg_react_rounds` | < 7 |
| `repeated_tool_call_rate` | < 5% |
| `terminated_by_limit_rate` | < 3% |

## 使用方式

```bash
# Smoke (5 个代表 case, 单次迭代)
mvn test -Dtest=EvalRunnerSmokeTest

# 真实评测 (LangChain4j + JdbcToolBackend, 需注入 env)
SLOW_SQL_LLM_BASE_URL=... SLOW_SQL_LLM_API_KEY=... SLOW_SQL_LLM_MODEL=... \
SLOW_SQL_DB_URL=... SLOW_SQL_DB_USER=... SLOW_SQL_DB_PASSWORD=... \
mvn -Dtest=LangChain4jEvalRunnerIT#fullEval test
```

输出 HTML 报告位于 `target/eval-reports/`, 包含三层指标 + Tool 异常分布 + Case 级通过率。

## 说明

- 多次跑取均值, 降低 LLM 采样波动对评测结果的影响
- 测试数据基于电商场景构造, `requirement` 字段模拟生产工单的一句话业务说明
