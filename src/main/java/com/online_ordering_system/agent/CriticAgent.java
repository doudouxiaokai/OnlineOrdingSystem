package com.online_ordering_system.agent;

import com.online_ordering_system.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Critic Agent（复核者）。
 * 在下单等 COMMIT 操作前，用独立的 LLM 调用复核「是否真的满足用户约束」，
 * 与主规划 Agent 形成多 agent 协作：主 agent 出方案，Critic 独立把关。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CriticAgent {

    private final LlmClient llmClient;

    private static final String CRITIC_PROMPT =
            "你是订单复核员。根据用户原始需求和即将执行的方案，判断方案是否真正满足需求。\n" +
            "检查项：预算是否超支、口味是否冲突（如用户要不辣却点了辣菜）、数量是否合理、是否遗漏关键需求。\n" +
            "只输出一个词：PASS 或 FAIL。FAIL 时附带一句原因，用逗号分隔。\n" +
            "格式：PASS 或 FAIL,原因";

    /**
     * 复核一个下单方案。
     * @return true=通过，false=不通过（附带原因）
     */
    public CriticResult review(String userIntent, String planDescription) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", CRITIC_PROMPT));
        messages.add(Map.of("role", "user", "content",
                "用户需求：" + userIntent + "\n方案：" + planDescription));

        LlmClient.LlmResponse response = llmClient.chat(messages, null);
        String verdict = response.content == null ? "" : response.content.trim();

        boolean pass = verdict.startsWith("PASS");
        String reason = "";
        if (verdict.contains(",")) {
            reason = verdict.substring(verdict.indexOf(",") + 1).trim();
        }
        log.info("Critic 复核结果：{}，原因：{}", pass ? "PASS" : "FAIL", reason);
        return new CriticResult(pass, reason);
    }

    public static class CriticResult {
        public final boolean pass;
        public final String reason;
        public CriticResult(boolean pass, String reason) {
            this.pass = pass;
            this.reason = reason;
        }
    }
}
