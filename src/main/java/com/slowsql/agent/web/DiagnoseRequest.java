package com.slowsql.agent.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/diagnose 请求体.
 *
 * sql:         必填, 待诊断的原始慢 SQL (单条 SELECT)
 * requirement: 可选, 业务工单文字 — agent 据此判定"前端可改 API" 等约束;
 *              缺失时按保守"不可改 API"假设走
 */
public record DiagnoseRequest(
        @JsonProperty("sql") String sql,
        @JsonProperty("requirement") String requirement
) {}
