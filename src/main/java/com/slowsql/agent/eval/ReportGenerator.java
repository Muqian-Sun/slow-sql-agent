package com.slowsql.agent.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告 HTML 渲染器.
 *
 * 用 StringBuilder 拼简单 HTML(不引 Thymeleaf 等模板引擎,减少依赖).
 * 输出包含:三层指标 + Tool 异常分布 + Case 详情.
 */
public class ReportGenerator {

    public Path writeHtml(EvalReport report, Path outputDir) throws IOException {
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        String filename = "eval-report-" + report.promptVersion() + "-"
                + report.runAt().atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".html";
        Path out = outputDir.resolve(filename);
        Files.writeString(out, render(report));
        return out;
    }

    private String render(EvalReport r) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>")
                .append("<title>Eval Report - ").append(r.promptVersion()).append("</title>")
                .append("<style>")
                .append("body{font-family:'SF Mono',Menlo,monospace;padding:24px;background:#1e1e1e;color:#d4d4d4;line-height:1.6}")
                .append("h1{color:#569cd6;margin-bottom:4px}h2{color:#4ec9b0;margin-top:32px;border-bottom:1px solid #444;padding-bottom:4px}")
                .append("table{border-collapse:collapse;margin:12px 0;font-size:14px}")
                .append("th,td{border:1px solid #444;padding:8px 14px;text-align:left}")
                .append("th{background:#2d2d2d;color:#dcdcaa}")
                .append(".pass{color:#6a9955;font-weight:bold}.fail{color:#f48771;font-weight:bold}.warn{color:#dcdcaa}")
                .append(".muted{color:#888;font-size:12px}")
                .append("details{margin:6px 0;padding:6px 12px;background:#252526;border-radius:4px}")
                .append("details summary{cursor:pointer;font-weight:bold;color:#9cdcfe}")
                .append("details[open] summary{margin-bottom:6px}")
                .append(".assumptions{font-size:12px;color:#bbb;margin:4px 0 0 12px;font-style:italic}")
                .append("</style></head><body>");

        // 头部
        sb.append("<h1>Eval Report — ").append(r.promptVersion()).append("</h1>")
                .append("<p class='muted'>Run at: ").append(r.runAt())
                .append(" &nbsp;|&nbsp; ").append(r.totalCases()).append(" cases · ")
                .append(r.totalRuns()).append(" runs</p>");

        // 第 1 层:业务价值
        // token_reduction_vs_baseline 需要 baseline ChatMemory 跑对照实验才出真数,
        // 在 baseline runner 接入前显式渲染 "pending", 不让 0.0% 误导读者.
        sb.append("<h2>📊 第 1 层 业务价值</h2>");
        appendMetricsTable(sb, new String[][]{
                {"p95_latency_ms", String.format("%.0f", r.p95LatencyMs()), "< 120 000"},
                {"high_confidence_rate", pct(r.highConfidenceRate()), "> 70%"},
                {"token_reduction_vs_baseline",
                        r.tokenReductionVsBaseline() == 0.0
                                ? "见 MemoryComparisonIT 独立产出"
                                : pct(r.tokenReductionVsBaseline()),
                        "~ 30%"},
        });

        // 第 2 层:效果
        // speedup_median / _max: 双跑测得的真实加速倍数. 比 cost_reduction (EXPLAIN rows 估算)
        // 更直接 — DBA 看"改写到底有没有效"的第一指标. 仅 row_hash 路径(deferred_join + general) 有真值,
        // cursor 路径靠 cost_reduction 替代.
        sb.append("<h2>🎯 第 2 层 效果(改写质量)</h2>");
        appendMetricsTable(sb, new String[][]{
                {"outcome_match_rate (整体)", pct(r.outcomeMatchRate()), "> 85%"},
                {"rewrite_precision (loose, 含 acceptable 兜底)", pct(r.rewritePrecision()), "> 85%"},
                {"rewrite_precision_strict (严格命中 expected)", pct(r.rewritePrecisionStrict()), "—"},
                {"&nbsp;&nbsp;delta (loose - strict)",
                        pct(r.rewritePrecision() - r.rewritePrecisionStrict()),
                        "越大说明 LLM 越倾向'保守标 unsupported 蒙对'"},
                {"unsupported_recall (期望 unsupported 的 case)", pct(r.unsupportedRecall()), "> 85%"},
                {"verification_pass_rate", pct(r.verificationPassRate()), "> 85%"},
                {"verification_undetermined_rate", pct(r.verificationUndeterminedRate()), "—"},
                {"cost_reduction_median (EXPLAIN rows)", pct(r.costReductionMedian()), "> 70%"},
                {"speedup_median (真实双跑)", String.format("%.1fx", r.speedupMedian()), "> 5x"},
                {"speedup_max", String.format("%.1fx", r.speedupMax()), "—"},
                {"business_context_compliance", pct(r.businessContextCompliance()), "100%"},
                {"assumptions_explicit_rate", pct(r.assumptionsExplicitRate()), "> 90%"},
        });

        // 第 3 层:行为
        // avg_summarizer_invocations: 历史摘要器在评测里平均触发次数. 简单 case (2-3 轮) 通常 0,
        // 复杂 case (5+ 轮, e.g. case_dp_dj_006) 才会触发 1+, 用以证伪"分层上下文是死代码".
        sb.append("<h2>🤖 第 3 层 行为(Agent 健康度)</h2>");
        appendMetricsTable(sb, new String[][]{
                {"avg_react_rounds", String.format("%.1f", r.avgReactRounds()), "< 7"},
                {"max_react_rounds", String.valueOf(r.maxReactRounds()), "< 12"},
                {"avg_summarizer_invocations", String.format("%.2f", r.avgSummarizerInvocations()),
                        "复杂 case ≥ 1"},
                {"repeated_tool_call_rate", pct(r.repeatedToolCallRate()), "< 5%"},
        });

        // Tool 异常分布
        sb.append("<h2>⚠️ Tool 异常分布</h2>");
        if (r.toolFailuresByReason().isEmpty()) {
            sb.append("<p class='pass'>无异常</p>");
        } else {
            sb.append("<table><tr><th>Reason</th><th>Count</th></tr>");
            for (Map.Entry<String, Integer> e : r.toolFailuresByReason().entrySet()) {
                sb.append("<tr><td>").append(e.getKey())
                        .append("</td><td>").append(e.getValue()).append("</td></tr>");
            }
            sb.append("</table>");
        }

        // Case 详情
        sb.append("<h2>📋 Case 详情</h2><table>")
                .append("<tr><th>Case ID</th><th>通过率</th><th>Outcome</th>")
                .append("<th>avg latency (ms)</th><th>avg tokens</th><th>avg rounds</th><th>错误</th></tr>");
        for (EvalReport.CaseAggregate ca : r.caseDetails()) {
            String cssClass = ca.passCount() == ca.totalRuns() ? "pass"
                    : ca.passCount() == 0 ? "fail" : "warn";
            sb.append("<tr><td>").append(ca.caseId())
                    .append("</td><td class='").append(cssClass).append("'>")
                    .append(ca.passCount()).append("/").append(ca.totalRuns())
                    .append("</td><td>").append(ca.mostCommonOutcome())
                    .append("</td><td>").append(String.format("%.0f", ca.avgLatencyMs()))
                    .append("</td><td>").append(String.format("%.0f", ca.avgTokens()))
                    .append("</td><td>").append(String.format("%.1f", ca.avgReactRounds()))
                    .append("</td><td class='muted'>").append(String.join("; ", ca.failureNotes()))
                    .append("</td></tr>");
        }
        sb.append("</table>");

        // Per-run 调试详情: 失败 case 用得上, 平时折叠.
        appendPerRunDetails(sb, r);

        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 渲染每个 case 的 run 级数据(折叠), 用于失败定位:
     *   - outcome 错 vs verify 错 vs parse 错 在聚合表看不出来, 这里能区分
     *   - summarizer 触发次数也在这里展示, 验证分层上下文真有在工作
     */
    private void appendPerRunDetails(StringBuilder sb, EvalReport r) {
        if (r.rawRuns() == null || r.rawRuns().isEmpty()) return;

        sb.append("<h2>🔍 Per-Run 调试详情</h2>")
                .append("<p class='muted'>展开查看 case 内每次 iter 的 outcome / verify / rounds / "
                        + "summarizer 触发等. 折叠态默认关闭, 只在 case 失败时按需展开.</p>");

        Map<String, List<RunResult>> byCase = new LinkedHashMap<>();
        for (RunResult run : r.rawRuns()) {
            byCase.computeIfAbsent(run.caseId(), k -> new ArrayList<>()).add(run);
        }

        for (Map.Entry<String, List<RunResult>> entry : byCase.entrySet()) {
            String caseId = entry.getKey();
            List<RunResult> runs = entry.getValue();
            long passed = runs.stream().filter(RunResult::outcomeMatched).count();
            String summaryCls = passed == runs.size() ? "pass"
                    : passed == 0 ? "fail" : "warn";

            sb.append("<details><summary class='").append(summaryCls).append("'>")
                    .append(caseId).append(" — ")
                    .append(passed).append("/").append(runs.size()).append(" pass")
                    .append("</summary>");
            sb.append("<table><tr><th>iter</th><th>outcome</th><th>conf</th><th>verify</th>")
                    .append("<th>cost↓</th><th>speedup</th><th>rounds</th><th>summarizer</th>")
                    .append("<th>assumptions</th><th>error</th></tr>");
            for (RunResult run : runs) {
                String outcomeStr = run.diagnosis() != null
                        ? run.diagnosis().outcome().toJsonValue() : "—";
                String confStr = run.diagnosis() != null
                        ? String.format("%.2f", run.diagnosis().confidence()) : "—";
                String verifyCls = "EQUIVALENT".equals(run.verificationStatus()) ? "pass"
                        : "NOT_EQUIVALENT".equals(run.verificationStatus()) ? "fail" : "muted";
                int assumeCount = run.diagnosis() != null && run.diagnosis().assumptions() != null
                        ? run.diagnosis().assumptions().size() : 0;
                String errCell = run.error() == null ? ""
                        : esc(run.error().length() > 80
                                ? run.error().substring(0, 80) + "…" : run.error());
                String outcomeCls = run.outcomeMatched() ? "pass" : "fail";

                String speedupStr = run.speedupX() == null
                        ? "—"
                        : String.format("%.1fx", run.speedupX());
                sb.append("<tr><td>").append(run.iteration())
                        .append("</td><td class='").append(outcomeCls).append("'>")
                        .append(outcomeStr)
                        .append("</td><td>").append(confStr)
                        .append("</td><td class='").append(verifyCls).append("'>")
                        .append(run.verificationStatus())
                        .append("</td><td>").append(String.format("%.1f%%", run.costReductionPercent() * 100))
                        .append("</td><td>").append(speedupStr)
                        .append("</td><td>").append(run.reactRounds())
                        .append("</td><td>").append(run.summarizerInvocations())
                        .append("</td><td>").append(assumeCount)
                        .append("</td><td class='muted'>").append(errCell)
                        .append("</td></tr>");
            }
            sb.append("</table>");

            // 展开第一个非错 run 的 assumptions 文本, 让 DBA 一眼看到判定依据
            runs.stream()
                    .filter(rn -> rn.diagnosis() != null
                            && rn.diagnosis().assumptions() != null
                            && !rn.diagnosis().assumptions().isEmpty())
                    .findFirst()
                    .ifPresent(rn -> {
                        sb.append("<div class='assumptions'>assumptions (iter ")
                                .append(rn.iteration()).append("):<br>");
                        for (String a : rn.diagnosis().assumptions()) {
                            sb.append("&nbsp;&nbsp;• ").append(esc(a)).append("<br>");
                        }
                        sb.append("</div>");
                    });

            sb.append("</details>");
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void appendMetricsTable(StringBuilder sb, String[][] rows) {
        sb.append("<table><tr><th>指标</th><th>值</th><th>目标</th></tr>");
        for (String[] row : rows) {
            sb.append("<tr><td>").append(row[0])
                    .append("</td><td><strong>").append(row[1]).append("</strong>")
                    .append("</td><td class='muted'>").append(row[2]).append("</td></tr>");
        }
        sb.append("</table>");
    }

    private String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}
