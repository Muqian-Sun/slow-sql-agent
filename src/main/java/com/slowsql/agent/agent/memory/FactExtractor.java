package com.slowsql.agent.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 从三个工具的 JSON 返回里抽取 KeyFact, 写入 KeyFactStore.
 *
 * 设计:
 *   - 输入: 工具名 + 原始 JSON 字符串(由 LangChain4j ChatMemory 在 ToolExecutionResultMessage 上拿到)
 *   - 失败容忍: JSON 解析失败 / 字段缺失都不抛, 静默跳过 — fact 是"加分项",
 *     拿不到不能阻塞主回路.
 *   - 不修改 ChatMemory 本身, 是纯采集器.
 */
public class FactExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public void extract(String toolName, String jsonResult, KeyFactStore store) {
        if (toolName == null || jsonResult == null || store == null) return;
        try {
            JsonNode root = mapper.readTree(jsonResult);
            switch (toolName) {
                case "getTableInfo" -> extractTableInfo(root, store);
                case "runExplain" -> extractExplain(root, store);
                case "verifyResultEquivalence" -> extractVerify(root, store);
                default -> { /* 未知工具静默跳过 */ }
            }
        } catch (Exception e) {
            // 容错: JSON parse 失败 / 字段缺失 — 不影响 agent 主回路
        }
    }

    // ------------------------------------------------------------------
    // getTableInfo: {status, table, indexes:[{name,unique,columns,cardinality}], estimated_rows}
    // ------------------------------------------------------------------
    private void extractTableInfo(JsonNode root, KeyFactStore store) {
        if (!"ok".equals(textOrEmpty(root, "status"))) return;
        String table = textOrEmpty(root, "table");
        if (table.isBlank()) return;

        StringBuilder detail = new StringBuilder();
        // PK + 主要索引摘要
        List<String> indexBlurbs = new ArrayList<>();
        String pk = null;
        JsonNode indexes = root.get("indexes");
        if (indexes != null && indexes.isArray()) {
            for (JsonNode idx : indexes) {
                String name = textOrEmpty(idx, "name");
                boolean unique = idx.has("unique") && idx.get("unique").asBoolean();
                String cols = joinArray(idx.get("columns"));
                long card = idx.has("cardinality") && !idx.get("cardinality").isNull()
                        ? idx.get("cardinality").asLong() : -1;
                if ("PRIMARY".equals(name) && cols != null) {
                    pk = cols;
                }
                indexBlurbs.add(String.format("%s%s(%s)%s",
                        name,
                        unique ? "[U]" : "",
                        cols == null ? "?" : cols,
                        card > 0 ? "~" + compactNum(card) : ""));
            }
        }
        long rows = root.has("estimated_rows") && !root.get("estimated_rows").isNull()
                ? root.get("estimated_rows").asLong() : -1;
        if (pk != null) detail.append("pk=").append(pk).append("; ");
        if (rows > 0) detail.append("rows~").append(compactNum(rows)).append("; ");
        if (!indexBlurbs.isEmpty()) detail.append("idx=").append(String.join(",", indexBlurbs));
        store.put(KeyFact.schema("table=" + table, detail.toString().trim()));
    }

    // ------------------------------------------------------------------
    // runExplain: {status, rows:[{table,type,key,rows,extra,...}]}
    // ------------------------------------------------------------------
    private void extractExplain(JsonNode root, KeyFactStore store) {
        if (!"ok".equals(textOrEmpty(root, "status"))) return;
        JsonNode rows = root.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) return;

        // sql 指纹由 LayeredChatMemory 拼到 detail 里(它知道 tool args), 这里只标 last_explain
        // 多次 explain 用插入序覆盖, 最后一次保留.
        StringBuilder detail = new StringBuilder();
        long totalRows = 0;
        for (JsonNode r : rows) {
            String table = textOrEmpty(r, "table");
            String type = textOrEmpty(r, "type");
            String key = textOrEmpty(r, "key");
            long n = r.has("rows") && !r.get("rows").isNull() ? r.get("rows").asLong() : 0;
            String extra = textOrEmpty(r, "extra");
            totalRows += Math.max(n, 0);
            if (detail.length() > 0) detail.append(" | ");
            detail.append(table).append(":").append(type)
                    .append("/").append(key.isBlank() ? "-" : key);
            if (n > 0) detail.append(",rows=").append(compactNum(n));
            if (!extra.isBlank()) detail.append(",extra=").append(extra);
        }
        if (totalRows > 0) {
            detail.append(" [total_rows=").append(compactNum(totalRows)).append(']');
        }
        store.put(KeyFact.plan("last_explain", detail.toString()));
    }

    // ------------------------------------------------------------------
    // verifyResultEquivalence: {status, strategy, reason, rows_reduction_pct, ...}
    // ------------------------------------------------------------------
    private void extractVerify(JsonNode root, KeyFactStore store) {
        String status = textOrEmpty(root, "status");
        if (status.isBlank()) return;
        String strategy = textOrEmpty(root, "strategy");
        StringBuilder detail = new StringBuilder(status.toUpperCase());
        if (!strategy.isBlank()) detail.append(" (").append(strategy).append(")");
        if ("pass".equals(status)) {
            if (root.has("rows_reduction_pct") && !root.get("rows_reduction_pct").isNull()) {
                detail.append(" reduction=")
                        .append(String.format("%.2f%%", root.get("rows_reduction_pct").asDouble()));
            }
        } else if ("fail".equals(status) || "error".equals(status)) {
            String reason = textOrEmpty(root, "reason");
            if (!reason.isBlank()) detail.append(" reason=").append(reason);
        }
        store.put(KeyFact.verify("last_verify", detail.toString()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String textOrEmpty(JsonNode node, String key) {
        if (node == null) return "";
        JsonNode v = node.get(key);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static String joinArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        arr.forEach(n -> parts.add(n.asText()));
        return String.join(",", parts);
    }

    /** 1234567 → 1.23M, 1234 → 1234. 把大数压短让 fact 不噪音. */
    private static String compactNum(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fG", n / 1_000_000_000.0);
    }

    /** 短指纹: SHA-1 前 4 字节 hex (8 char), 用于 sql / args 标识. */
    public static String fingerprint(String s) {
        if (s == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
