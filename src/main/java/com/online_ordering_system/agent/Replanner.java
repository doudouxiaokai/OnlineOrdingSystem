package com.online_ordering_system.agent;

import com.online_ordering_system.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 重规划器：当执行中断言失败（如库存不足）时，重新规划「剩余未完成」的步骤。
 * 关键特性：已完成的步骤不重跑，只重写剩余子图。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Replanner {

    private final LlmClient llmClient;
    private final Planner planner;
    private final PlanValidator validator;

    /** 最近一次 replan() 累计消耗的 token 数 */
    private int lastTokens = 0;

    /**
     * 根据失败信息重新规划剩余步骤。
     * @param plan 原计划（部分步骤已 DONE）
     * @param failedStepId 失败的步骤
     * @param failureReason 失败原因
     * @return 新的剩余步骤列表，若无法重规划返回 null
     */
    public List<PlanStep> replan(Plan plan, String failedStepId, String failureReason) {
        this.lastTokens = 0;
        // 已完成的步骤集合
        List<String> doneStepIds = new ArrayList<>();
        for (PlanStep s : plan.getSteps()) {
            if ("DONE".equals(s.getStatus())) doneStepIds.add(s.getStepId());
        }

        log.info("重规划：已完成步骤 {}，失败步骤 {}，原因 {}", doneStepIds, failedStepId, failureReason);

        // 让 LLM 只规划「剩余」步骤
        String prompt =
                "你之前的计划执行到步骤 " + failedStepId + " 失败了，原因是：" + failureReason + "。\n" +
                "已成功完成的步骤：[" + String.join(", ", doneStepIds) + "]，这些步骤的结果已拿到，不要重复执行。\n" +
                "请重新规划「剩余」需要做的事，输出 JSON，格式同之前：\n" +
                "{\"steps\":[{\"stepId\":\"s_new1\",\"tool\":\"...\",\"args\":{...},\"dependsOn\":[],\"expect\":\"...\"},...]}\n" +
                "注意：\n" +
                "1. args 可以引用已完成步骤的结果，用 ${stepId.字段名}，但已完成的步骤不要放进 dependsOn，也不要重复执行。\n" +
                "2. dependsOn 只用来表达「新步骤之间」的依赖关系。只输出 JSON。";

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompt));
        messages.add(Map.of("role", "user", "content", "请给出剩余步骤计划"));

        LlmClient.LlmResponse response = llmClient.chat(messages, null);
        lastTokens += response.tokenUsed;
        String json = extractJson(response.content);
        if (json == null) return null;

        Plan partial = validator.validateAndRepair(json);
        return partial == null ? null : partial.getSteps();
    }

    public int getLastTokens() { return lastTokens; }

    private String extractJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return null;
    }
}
