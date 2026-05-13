package com.slowsql.agent.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
                .append("</style></head><body>");

        // 头部
        sb.append("<h1>Eval Report — ").append(r.promptVersion()).append("</h1>")
                .append("<p class='muted'>Run at: ").append(r.runAt())
                .append(" &nbsp;|&nbsp; ").append(r.totalCases()).append(" cases · ")
                .append(r.totalRuns()).append(" runs</p>");

        // 第 1 层:业务价值
        sb.append("<h2>📊 第 1 层 业务价值</h2>");
        appendMetricsTable(sb, new String[][]{
                {"p95_latency_ms", String.format("%.0f", r.p95LatencyMs()), "< 120 000"},
                {"high_confidence_rate", pct(r.highConfidenceRate()), "> 70%"},
                {"token_reduction_vs_baseline", pct(r.tokenReductionVsBaseline()), "~ 30%"},
        });

        // 第 2 层:效果
        sb.append("<h2>🎯 第 2 层 效果(改写质量)</h2>");
        appendMetricsTable(sb, new String[][]{
                {"outcome_match_rate", pct(r.outcomeMatchRate()), "> 85%"},
                {"verification_pass_rate", pct(r.verificationPassRate()), "> 85%"},
                {"verification_undetermined_rate", pct(r.verificationUndeterminedRate()), "—"},
                {"cost_reduction_median", pct(r.costReductionMedian()), "> 70%"},
                {"business_context_compliance", pct(r.businessContextCompliance()), "100%"},
                {"assumptions_explicit_rate", pct(r.assumptionsExplicitRate()), "> 90%"},
        });

        // 第 3 层:行为
        sb.append("<h2>🤖 第 3 层 行为(Agent 健康度)</h2>");
        appendMetricsTable(sb, new String[][]{
                {"avg_react_rounds", String.format("%.1f", r.avgReactRounds()), "< 7"},
                {"max_react_rounds", String.valueOf(r.maxReactRounds()), "< 12"},
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

        sb.append("</body></html>");
        return sb.toString();
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
