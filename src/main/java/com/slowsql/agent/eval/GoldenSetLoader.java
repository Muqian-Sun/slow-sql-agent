package com.slowsql.agent.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 samples/golden_set.json 加载评测 case.
 */
public class GoldenSetLoader {

    /** Smoke 子集 — 三种合法 outcome 的代表 case, 5 个覆盖典型场景. */
    public static final List<String> SMOKE_CASE_IDS = List.of(
            "case_dp_dj_001",   // rewritten_deferred_join 代表
            "case_dp_cur_001",  // rewritten_cursor 代表
            "case_dp_idx_001",  // unsupported / 缺索引 + DDL 建议
            "case_dp_oos_002",  // unsupported / GROUP BY OLAP 越界
            "case_dp_oos_004"   // unsupported / DML 安全拒绝
    );

    private final ObjectMapper mapper;

    public GoldenSetLoader() {
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public List<GoldenCase> loadAll(Path goldenSetJson) throws IOException {
        JsonNode root = mapper.readTree(Files.readString(goldenSetJson));
        JsonNode cases = root.get("cases");
        if (cases == null || !cases.isArray()) {
            throw new IOException("Invalid golden_set.json: missing 'cases' array");
        }
        List<GoldenCase> result = new ArrayList<>();
        for (JsonNode node : cases) {
            result.add(mapper.treeToValue(node, GoldenCase.class));
        }
        return result;
    }

    public List<GoldenCase> loadByIds(Path goldenSetJson, List<String> caseIds) throws IOException {
        return loadAll(goldenSetJson).stream()
                .filter(c -> caseIds.contains(c.id()))
                .toList();
    }

    public List<GoldenCase> loadSmoke(Path goldenSetJson) throws IOException {
        return loadByIds(goldenSetJson, SMOKE_CASE_IDS);
    }
}
