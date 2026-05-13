package com.slowsql.agent.agent;

/**
 * 业务上下文 — 调用方在工单里随手写的一句业务说明.
 *
 * 设计取舍:
 *   原本设计为结构化对象(scenario / canModifyApi / qps / sla 等), 但那等于把
 *   agent 该做的"从自然语言抽取语义"工作提前做掉了, 没法真实评测 agent 的理解能力.
 *
 *   改为单一自由文本字段 requirement, 模拟生产场景:
 *     - 开发提交慢 SQL 工单时通常只会写一句"C 端 APP 时间线, 前端可改"
 *     - Agent 需要自己识别"前端可改"=允许游标分页这种语义
 *
 *   可为 null/blank, 此时 agent 应基于通用启发式给方案 + 显式 assumption.
 */
public record BusinessContext(String requirement) {

    public static BusinessContext empty() {
        return new BusinessContext(null);
    }

    public static BusinessContext of(String requirement) {
        return new BusinessContext(requirement);
    }

    public boolean hasRequirement() {
        return requirement != null && !requirement.isBlank();
    }
}
