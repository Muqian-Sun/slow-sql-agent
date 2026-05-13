# 评测黄金集

20 条标注 SQL case,专注 MySQL 深分页场景,作为 Agent 端到端评测的长期基准。

## 数据集统计

| 维度 | 分布 |
|---|---|
| 总数 | 20 |
| 简单深分页(单表) | 6 |
| JOIN 深分页(2 表) | 4 |
| 多表深分页(4-5 表) | 2 |
| tie-breaker 场景(排序列非 PK) | 2 |
| 业务可改 API(推荐游标分页) | 2 |
| 业务不可改 API(推荐延迟关联) | 2 |
| 反例(offset 不大,无需优化) | 1 |
| 改写需先加索引 | 1 |

期望产出分布:`rewritten_deferred_join` 13、`rewritten_cursor` 2、`suggest_index` 1、`no_optimization_needed` 1、`unsupported` 1,2 条标注多种 acceptable outcomes。

## 文件结构

```
samples/
├── README.md
├── schema.sql      # 8 张测试表 schema(电商场景)
└── golden_set.json # 20 条标注 case
```

## Schema 设计

8 张表覆盖电商核心场景,数据量到千万级。每张表都有 PRIMARY KEY(`id BIGINT AUTO_INCREMENT`),支持基于主键的等价性验证。

| 表 | 行数 | 备注 |
|---|---|---|
| `users` | ~500 万 | 用户表 |
| `merchants` | ~10 万 | 商家表 |
| `categories` | ~1000 | 分类(小表) |
| `products` | ~100 万 | 商品 |
| `orders` | ~1000 万 | 深分页主战场 |
| `order_items` | ~2000 万 | 订单明细 |
| `reviews` | ~300 万 | 评论(含 TEXT) |
| `user_actions` | ~1500 万 | 用户行为 |

详见 `schema.sql`。

## Case 结构

```json
{
  "id": "case_dp_s_001",
  "complexity": "simple",
  "tags": ["deep_pagination", "single_table", "pk_ordered"],
  "input": {
    "sql": "...",
    "schema_required": ["orders"],
    "data_volume_hint": "...",
    "business_context": {
      "can_modify_api": true,
      "data_freshness": "realtime"
    }
  },
  "expected": {
    "expected_outcome": "rewritten_deferred_join",
    "acceptable_outcomes": ["rewritten_deferred_join", "rewritten_cursor"],
    "min_cost_reduction_percent": 80,
    "must_pass_verification": true,
    "notes_for_evaluator": "..."
  },
  "notes": "case 设计意图"
}
```

## 设计原则

- **稳定性**:整个项目周期内数据集不变。任何修改需重跑历史 baseline 防止指标失真。
- **复杂度梯度**:简单(单表深分页,Agent 应高通过)、中等(JOIN / tie-breaker / 业务约束,主要区分度)、复杂(4-5 表 JOIN 深分页)。
- **业务约束驱动**:同一 SQL 在不同 `business_context` 下推荐结果不同(可改 API → 游标分页;不可改 → 延迟关联),验证 Agent 决策遵循业务约束。
- **反向 case 测克制力**:`case_dp_neg_001`(offset 不大无需优化)、`case_dp_m_003`(排序在非主表应标 UNSUPPORTED)。

## 评测指标

### 业务价值

| 指标 | 目标 |
|---|---|
| `high_confidence_rate` | > 70% |
| `p95_latency_ms` | < 120 000 |
| `token_reduction_vs_baseline` | ~30%(对比默认滑窗) |

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
| 各类异常占比 | 各 < 10% |

## 使用方式

```bash
# 跑全量 + 多次取均值
mvn test -Peval-full -Diter=3

# 跑核心 5 条 smoke
mvn test -Peval -Dcases=smoke

# 跑指定 case
mvn test -Peval -Dcases=case_dp_c_001,case_dp_c_002
```

输出 HTML 报告位于 `target/eval-reports/`,包含三层指标 + Tool 异常分布 + Case 级通过率。

## 说明

- 多次跑取均值,降低 LLM 采样波动对评测结果的影响
- 测试数据基于电商场景构造,`business_context` 字段在评测集中由人工标注模拟,生产接入由调用方传入
