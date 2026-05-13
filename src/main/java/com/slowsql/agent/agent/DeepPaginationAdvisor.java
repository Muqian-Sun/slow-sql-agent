package com.slowsql.agent.agent;

import com.slowsql.agent.llm.SystemPrompts;
import dev.langchain4j.service.SystemMessage;

/**
 * LangChain4j AiService 接口 — 由框架基于 ChatModel + Tools 自动织入实现.
 *
 * diagnose(...) 入参为已经渲染好的 user prompt(包含 SQL 与业务上下文),
 * 出参为 LLM 最终输出(应为单个 JSON 对象, 由 DiagnosisOutputParser 解析).
 */
public interface DeepPaginationAdvisor {

    @SystemMessage(SystemPrompts.DEEP_PAGINATION_DIAGNOSIS)
    String diagnose(String userPrompt);
}
