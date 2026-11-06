package com.online_ordering_system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.agent.*;
import com.online_ordering_system.domain.AgentRun;
import com.online_ordering_system.domain.AgentStep;
import com.online_ordering_system.llm.LlmClient;
import com.online_ordering_system.mapper.AgentRunMapper;
import com.online_ordering_system.mapper.AgentStepMapper;
import com.online_ordering_system.tool.AgentTool;
import com.online_ordering_system.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 显式规划编排器（P2 主链路，已接入 /api/agent/chat）。
 * 流程：Planner 生成计划 → Executor 拓扑执行（内置 Guardrails 拦截）→
 *       失败触发 Replanner 局部重写 → 循环直到完成或超限 → 生成最终自然语言回复。
 *
 * 复杂度自适应：默认走显式规划；若计划只有 1 步且为 READ 工具，则直接执行一次，
 * 不进入重规划循环，让「计划长短」自动决定复杂度，不做人肉判断。
 * 每一步落库 AgentRun/AgentStep，支持断点恢复与 Trace 回放。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlannerOrchestrator {

    private final Planner planner;
    private final Executor executor;
    private final Replanner replanner;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final UserPrefService userPrefService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最大重规划次数，防止死循环 */
    private static final int MAX_REPLAN = 3;

    /** 最终回复提示词：对用户只说自然语言，绝不提 ID */
    private static final String REPLY_PROMPT =
            "你是外卖点餐助手。规则：\n" +
            "1. 对用户永远只用自然语言（店名、菜名），绝不提及任何 ID（restaurantId、dishId、orderId 等）。\n" +
            "2. 根据给定的工具执行结果，用简洁自然的中文总结，回答用户的原始意图。\n" +
            "3. 如果某一步失败或信息不足，如实告知用户，并说明下一步建议。";

    /** 编排过程监听器：plan 就绪 / step 生命周期 / 最终完成 */
    public interface OrchestrationListener extends Executor.StepListener {
        default void onPlanReady(Plan plan) {}
        default void onFinished(Map<String, Object> result) {}
    }

    /**
     * 显式规划主入口。
     * @return 与 AgentService.run 对齐的结构 {type, content, runId, steps, tokenUsed, plan}
     */
    public Map<String, Object> run(String userId, String sessionId, String userText) {
        return run(userId, sessionId, userText, null);
    }

    /**
     * 显式规划主入口（带过程监听，供 WebSocket 流式推送复用）。
     */
    public Map<String, Object> run(String userId, String sessionId, String userText, OrchestrationListener listener) {
        AgentRun run = newRun(userId, sessionId, userText);
        int totalTokens = 0;

        try {
            // 1. 构建规划上下文（用户身份 + 偏好记忆）
            String extraContext = buildContext(userId);

            // 2. 生成计划
            Plan plan = planner.plan(userText, extraContext, 2);
            if (plan == null) {
                Map<String, Object> r = finish(run, "FAILED", "抱歉，我暂时无法理解这个请求，请换个说法试试。", 0, null);
                if (listener != null) listener.onFinished(r);
                return r;
            }
            plan.setRunId(run.getRunId());
            totalTokens += planner.getLastTokens();

            // 落库计划快照
            run.setPlanJson(toJson(plan));
            run.setStatus("EXECUTING");
            run.setUpdatedAt(LocalDateTime.now());
            agentRunMapper.updateById(run);
            if (listener != null) listener.onPlanReady(plan);

            // 3. 执行（单步 READ 直接执行，否则走重规划循环）
            Executor.StepListener stepListener = compose(
                    new PersistingStepListener(run.getRunId()), listener);
            boolean success = isSimpleRead(plan)
                    ? executor.execute(plan, stepListener)
                    : executeWithReplan(plan, stepListener);
            totalTokens += replanner.getLastTokens();

            if (!success) {
                Map<String, Object> r = finish(run, "FAILED", "抱歉，执行过程中出了点问题，未能完成您的请求。", totalTokens, plan);
                if (listener != null) listener.onFinished(r);
                return r;
            }

            // 4. 生成最终自然语言回复
            LlmClient.LlmResponse replyResp = generateFinalReply(userText, plan);
            totalTokens += replyResp.tokenUsed;

            Map<String, Object> r = finish(run, "DONE", replyResp.content, totalTokens, plan);
            if (listener != null) listener.onFinished(r);
            return r;
        } catch (Exception e) {
            log.error("显式规划执行失败", e);
            Map<String, Object> r = finish(run, "FAILED", "抱歉，处理出错了：" + e.getMessage(), totalTokens, null);
            if (listener != null) listener.onFinished(r);
            return r;
        }
    }

    /** 组合两个 StepListener（落库 + 外部推送） */
    private Executor.StepListener compose(Executor.StepListener a, Executor.StepListener b) {
        if (a == null) return b;
        if (b == null) return a;
        return new Executor.StepListener() {
            @Override public void onStepStart(PlanStep s) { a.onStepStart(s); b.onStepStart(s); }
            @Override public void onStepDone(PlanStep s) { a.onStepDone(s); b.onStepDone(s); }
            @Override public void onStepFailed(PlanStep s, Exception e) { a.onStepFailed(s, e); b.onStepFailed(s, e); }
        };
    }

    /** 判断是否为「单步 READ」简单任务 */
    private boolean isSimpleRead(Plan plan) {
        List<PlanStep> steps = plan.getSteps();
        if (steps == null || steps.size() != 1) return false;
        ToolRegistry.ToolDefinition def = toolRegistry.get(steps.get(0).getTool());
        return def != null && def.sideEffect == AgentTool.SideEffect.READ;
    }

    /** 执行 + 局部重规划循环（复杂任务） */
    private boolean executeWithReplan(Plan plan, Executor.StepListener listener) {
        int replanCount = 0;
        while (true) {
            if (executor.execute(plan, listener)) {
                return true;
            }

            PlanStep failed = plan.getSteps().stream()
                    .filter(s -> "FAILED".equals(s.getStatus()))
                    .findFirst().orElse(null);
            if (failed == null || replanCount >= MAX_REPLAN) {
                return false;
            }

            String reason = String.valueOf(failed.getOutput().getOrDefault("error", "未知错误"));
            replanCount++;
            List<PlanStep> remaining = replanner.replan(plan, failed.getStepId(), reason);
            if (remaining == null || remaining.isEmpty()) {
                return false;
            }

            // 用新步骤替换旧的「未完成」步骤，保留已完成步骤
            List<PlanStep> newSteps = new ArrayList<>();
            for (PlanStep s : plan.getSteps()) {
                if ("DONE".equals(s.getStatus())) newSteps.add(s);
            }
            newSteps.addAll(remaining);
            plan.setSteps(newSteps);
        }
    }

    /** 生成最终自然语言回复 */
    private LlmClient.LlmResponse generateFinalReply(String goal, Plan plan) {
        StringBuilder summary = new StringBuilder();
        for (PlanStep s : plan.getSteps()) {
            summary.append("- 步骤 ").append(s.getStepId()).append("（").append(s.getTool()).append("）")
                   .append("状态：").append(s.getStatus());
            if (s.getOutput() != null && !s.getOutput().isEmpty()) {
                summary.append("，结果：").append(toJson(s.getOutput()));
            }
            summary.append("\n");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", REPLY_PROMPT));
        messages.add(Map.of("role", "user", "content",
                "用户原始意图：" + goal + "\n工具执行结果：\n" + summary));
        return llmClient.chat(messages, null);
    }

    /** 构建注入规划器的上下文 */
    private String buildContext(String userId) {
        StringBuilder sb = new StringBuilder();
        if (userId != null && !userId.isEmpty()) {
            sb.append("当前用户 ID：").append(userId).append("\n");
        }
        String pref = userPrefService.buildPreferenceSummary(userId);
        if (pref != null && !pref.isEmpty()) {
            sb.append(pref).append("\n");
        }
        return sb.toString();
    }

    private AgentRun newRun(String userId, String sessionId, String userText) {
        AgentRun run = new AgentRun();
        run.setRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setGoal(userText);
        run.setStatus("PLANNING");
        run.setTokenUsed(0);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        agentRunMapper.insert(run);
        return run;
    }

    private Map<String, Object> finish(AgentRun run, String status, String reply, int totalTokens, Plan plan) {
        run.setStatus(status);
        run.setResultJson("{\"reply\":\"" + escape(reply) + "\"}");
        run.setTokenUsed(totalTokens);
        run.setUpdatedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);

        Map<String, Object> result = new HashMap<>();
        result.put("type", "text");
        result.put("content", reply);
        result.put("runId", run.getRunId());
        result.put("tokenUsed", totalTokens);
        if (plan != null) {
            result.put("steps", plan.getSteps().size());
            result.put("plan", toPlanView(plan));
        } else {
            result.put("steps", 0);
        }
        return result;
    }

    /** 把 Plan 转成前端可展示的 DAG 结构 */
    private List<Map<String, Object>> toPlanView(Plan plan) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep s : plan.getSteps()) {
            Map<String, Object> m = new HashMap<>();
            m.put("stepId", s.getStepId());
            m.put("tool", s.getTool());
            m.put("status", s.getStatus());
            m.put("dependsOn", s.getDependsOn() == null ? List.of() : s.getDependsOn());
            m.put("expect", s.getExpect());
            m.put("output", s.getOutput());
            steps.add(m);
        }
        return steps;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 步骤落库监听器：每个步骤开始/结束时写 AgentStep */
    private class PersistingStepListener implements Executor.StepListener {
        private final String runId;
        private final AtomicInteger seq = new AtomicInteger(0);
        private final Map<String, String> dbIdByStepId = new HashMap<>();

        PersistingStepListener(String runId) {
            this.runId = runId;
        }

        @Override
        public void onStepStart(PlanStep step) {
            String dbId = UUID.randomUUID().toString().replace("-", "");
            dbIdByStepId.put(step.getStepId(), dbId);

            AgentStep s = new AgentStep();
            s.setStepId(dbId);
            s.setRunId(runId);
            s.setSeq(seq.incrementAndGet());
            s.setToolName(step.getTool());
            s.setArgsJson(toJson(step.getArgs()));
            s.setExpect(step.getExpect());
            s.setStatus("RUNNING");
            s.setAttempt(0);
            s.setStartedAt(LocalDateTime.now());
            agentStepMapper.insert(s);
        }

        @Override
        public void onStepDone(PlanStep step) {
            updateStep(step, "DONE", null);
        }

        @Override
        public void onStepFailed(PlanStep step, Exception e) {
            updateStep(step, "FAILED", e.getMessage());
        }

        private void updateStep(PlanStep step, String status, String error) {
            String dbId = dbIdByStepId.get(step.getStepId());
            if (dbId == null) return;
            AgentStep s = new AgentStep();
            s.setStepId(dbId);
            s.setStatus(status);
            s.setOutputJson(toJson(step.getOutput()));
            s.setErrorMsg(error);
            s.setFinishedAt(LocalDateTime.now());
            agentStepMapper.updateById(s);
        }
    }
}
