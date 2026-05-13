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

    /** Smoke 测试默认覆盖的 case ID(5 条核心代表,1 分钟跑完) */
    public static final List<String> SMOKE_CASE_IDS = List.of(
            "case_dp_s_001",   // 简单单表深分页
            "case_dp_m_001",   // 2 表 JOIN 深分页
            "case_dp_c_001",   // 4 表 JOIN 深分页(亮点)
            "case_dp_t_001",   // tie-breaker 场景
            "case_dp_neg_001"  // 反例:offset 不大不该优化
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
