package com.slowsql.agent.diagnosis.tools.result;

import com.slowsql.agent.diagnosis.memory.KeyFactStore;

/**
 * 任何工具返回 record 都可以实现这个接口, 自己声明"哪些字段进 KeyFactStore".
 *
 * 设计意图: 把 fact 抽取从中心化 dispatch (历史的 FactExtractor switch on toolName + JSON 字段
 * 硬访问) 移到 record 自己 — 字段重命名会直接编译失败而不是静默丢 fact, 新加工具时也只能
 * 在编译期决定要不要导出 fact, 不会"忘了同步".
 *
 * 调用时机: DiagnosisTools 在 @Tool 方法 return 给 LLM 之前, 直接调一次本接口的
 * exportFactsTo. record 自己决定从哪些字段取数据, 怎么压缩成 KeyFact.
 *
 * 失败容忍约定: 实现里**不应**抛异常 — fact 是加分项, 拿不到不能影响主回路. 字段为 null /
 * status=error 等情况内部判空 + 静默返回.
 */
public interface FactExportable {

    /**
     * 把本次工具调用产生的 fact 写入 store. 实现里直接访问 record 字段, 不依赖外部 JSON 解析.
     * 主回路保证 store 非 null. 实现不应抛.
     */
    void exportFactsTo(KeyFactStore store);
}
