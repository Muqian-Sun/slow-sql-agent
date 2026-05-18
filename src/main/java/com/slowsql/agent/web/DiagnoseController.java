package com.slowsql.agent.web;

import com.slowsql.agent.agent.BusinessContext;
import com.slowsql.agent.agent.DiagnosisResult;
import com.slowsql.agent.agent.LangChain4jDiagnosisAgent;
import com.slowsql.agent.llm.LlmConfig;
import com.slowsql.agent.tools.ToolBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 唯一对外端点: POST /api/diagnose
 *
 * 设计:
 *   - Agent 实例 per-request 创建, 不做 bean — 每次调用拿独立 AgentStatsListener + LayeredChatMemory,
 *     防止跨请求状态污染.
 *   - LlmConfig / ToolBackend 走 bean 复用 (HikariCP 池 / ChatModel 客户端共享).
 *   - 调用同步阻塞, 单请求耗时 30s-2min 量级 (LLM 多轮 ReAct). 服务起来后这个端点是慢端点,
 *     生产部署需要前置 30s+ 的 Nginx / ALB timeout. 此外建议异步化, 但本服务先做最小可用版本.
 */
@RestController
@RequestMapping("/api")
public class DiagnoseController {

    private static final Logger log = LoggerFactory.getLogger(DiagnoseController.class);

    private final LlmConfig llmConfig;
    private final ToolBackend toolBackend;

    public DiagnoseController(LlmConfig llmConfig, ToolBackend toolBackend) {
        this.llmConfig = llmConfig;
        this.toolBackend = toolBackend;
    }

    @PostMapping(value = "/diagnose", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> diagnose(@RequestBody DiagnoseRequest req) {
        if (req == null || req.sql() == null || req.sql().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "field 'sql' is required"));
        }

        long t0 = System.nanoTime();
        try {
            LangChain4jDiagnosisAgent agent = new LangChain4jDiagnosisAgent(llmConfig, toolBackend);
            BusinessContext ctx = req.requirement() == null || req.requirement().isBlank()
                    ? BusinessContext.empty()
                    : BusinessContext.of(req.requirement());

            DiagnosisResult result = agent.diagnose(req.sql(), ctx);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            log.info("diagnose ok: outcome={}, confidence={}, rounds={}, summarizer={}, elapsed={}ms",
                    result.outcome(), result.confidence(),
                    agent.stats().reactRounds(), agent.summarizerInvocations(), elapsedMs);

            return ResponseEntity.ok(Map.of(
                    "result", result,
                    "stats", Map.of(
                            "react_rounds", agent.stats().reactRounds(),
                            "total_tool_calls", agent.stats().totalToolCalls(),
                            "summarizer_invocations", agent.summarizerInvocations(),
                            "total_tokens", agent.stats().totalTokens(),
                            "elapsed_ms", elapsedMs)));
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.error("diagnose failed after {}ms: {}", elapsedMs, e.toString(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "diagnose_failed",
                            "message", e.getMessage() == null ? e.toString() : e.getMessage(),
                            "elapsed_ms", elapsedMs));
        }
    }
}
