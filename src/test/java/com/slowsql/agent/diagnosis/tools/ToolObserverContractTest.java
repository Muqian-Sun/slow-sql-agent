package com.slowsql.agent.diagnosis.tools;

import com.slowsql.agent.dbinspect.MockToolBackend;
import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具层只依赖 ToolObserver 接口的契约测试.
 *
 * 这个测试**没有 import 任何 eval 包的类** — 它证明 DiagnosisTools 可以跟 eval 完全无关的
 * Observer 实现一起工作. 这是 ADR-A "工具层反向依赖 eval/AgentStatsListener" 修复后必须保持的契约.
 *
 * 如果将来有人在 DiagnosisTools 里加新的 stats hook (eg. onMemorySpilled), 这个测试不需要改 —
 * 但 ToolObserver 接口需要加新 method, 而本测试里的 SpyObserver 也需要实现. 编译期约束.
 */
class ToolObserverContractTest {

    /** 纯测试用 Observer, 不依赖 eval 包. */
    static class SpyObserver implements ToolObserver {
        final List<String> toolCalls = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<String> verifyResults = new ArrayList<>();

        @Override
        public void onToolCall(String toolName, String argsFingerprint) {
            toolCalls.add(toolName + ":" + argsFingerprint);
        }

        @Override
        public void onToolFailure(String reason) {
            failures.add(reason);
        }

        @Override
        public void onVerifyResult(boolean pass, Double reductionPct, Double speedupX) {
            verifyResults.add(pass + ":" + reductionPct + ":" + speedupX);
        }
    }

    @Test
    void getTableInfoNotifiesObserver() {
        SpyObserver observer = new SpyObserver();
        DiagnosisTools tools = new DiagnosisTools(new MockToolBackend(), observer, new KeyFactStore());

        tools.getTableInfo("orders");

        assertThat(observer.toolCalls).hasSize(1);
        assertThat(observer.toolCalls.get(0)).startsWith("getTableInfo:");
        assertThat(observer.failures).isEmpty();
    }

    @Test
    void invalidTableNotifiesObserverFailure() {
        SpyObserver observer = new SpyObserver();
        DiagnosisTools tools = new DiagnosisTools(new MockToolBackend(), observer, new KeyFactStore());

        tools.getTableInfo("nonexistent_table");

        // tool call 被记 + 还要标 failure (mock 会返回 status=error)
        assertThat(observer.toolCalls).hasSize(1);
        assertThat(observer.failures).isNotEmpty();
    }

    @Test
    void verifyNotifiesObserverVerifyResult() {
        SpyObserver observer = new SpyObserver();
        DiagnosisTools tools = new DiagnosisTools(new MockToolBackend(), observer, new KeyFactStore());

        tools.verifyResultEquivalence("SELECT 1", "SELECT 1");

        // 至少一次 onVerifyResult 被调 (mock 一般返回 pass)
        assertThat(observer.verifyResults).hasSize(1);
    }

    @Test
    void diagnosisToolsCtorAcceptsToolObserverNotJustAgentStatsListener() {
        // 编译期契约: ctor 参数类型必须是 ToolObserver, 不是具体 AgentStatsListener.
        // 这条让"换 observer 实现"成为可能, 不被 eval 包绑死.
        ToolObserver observer = new SpyObserver();
        new DiagnosisTools(new MockToolBackend(), observer, new KeyFactStore());
        // 编译过即通过
    }
}
