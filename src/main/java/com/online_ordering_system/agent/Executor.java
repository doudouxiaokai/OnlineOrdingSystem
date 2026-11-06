package com.online_ordering_system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行器：按拓扑顺序执行计划步骤。
 * - 依赖未完成的步骤等待（本版简化为串行顺序执行，P2.5 可升级并行）
 * - 解析 args 中的 ${stepId.field} 占位符，注入前序步骤的真实产出
 * - 执行前调用 Guardrails 做副作用分级与安全拦截
 * - 每个步骤的状态变化通过 StepListener 回调（供落库、WebSocket 流式推送复用）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Executor {

    private final ToolRegistry toolRegistry;
    private final Guardrails guardrails;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^.}]+)\\.([^}]+)}");

    /** 步骤执行监听器：onStepStart / onStepDone / onStepFailed */
    public interface StepListener {
        void onStepStart(PlanStep step);
        void onStepDone(PlanStep step);
        void onStepFailed(PlanStep step, Exception e);
    }

    /**
     * 执行整个计划（无监听器）。返回是否全部成功。
     */
    public boolean execute(Plan plan) {
        return execute(plan, null);
    }

    /**
     * 执行整个计划。返回是否全部成功。
     * 执行过程中每个 step 的 status/output 会被就地更新。
     */
    public boolean execute(Plan plan, StepListener listener) {
        for (PlanStep step : plan.getSteps()) {
            // 依赖未满足则标记 SKIPPED（正常流程下由拓扑保证不会发生）
            if (!step.depsResolved(plan.getSteps())) {
                step.setStatus("SKIPPED");
                log.warn("步骤 {} 依赖未满足，跳过", step.getStepId());
                continue;
            }

            step.setStatus("RUNNING");
            if (listener != null) listener.onStepStart(step);
            try {
                // 解析占位符
                Map<String, Object> resolvedArgs = resolveArgs(step.getArgs(), plan);

                // 守护规则：副作用分级 + 金额阈值 + 人工确认拦截
                String block = guardrails.check(step.getTool(), resolvedArgs);
                if (block != null) {
                    throw new IllegalStateException("已被安全规则拦截：" + block);
                }

                Object result = toolRegistry.invoke(step.getTool(), resolvedArgs);
                step.setOutput(convertToMap(result));
                step.setStatus("DONE");
                if (listener != null) listener.onStepDone(step);
                log.info("步骤 {}（{}）执行成功", step.getStepId(), step.getTool());
            } catch (Exception e) {
                step.setStatus("FAILED");
                step.setOutput(Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                if (listener != null) listener.onStepFailed(step, e);
                log.error("步骤 {}（{}）执行失败：{}", step.getStepId(), step.getTool(), e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** 把 args 中的占位符替换为前序步骤的真实产出值 */
    private Map<String, Object> resolveArgs(Map<String, Object> args, Plan plan) {
        Map<String, Object> resolved = new HashMap<>();
        if (args == null) return resolved;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), plan));
        }
        return resolved;
    }

    private Object resolveValue(Object value, Plan plan) {
        if (value instanceof String) {
            String str = (String) value;
            Matcher m = PLACEHOLDER.matcher(str);
            if (m.matches()) {
                String stepId = m.group(1);
                String path = m.group(2);
                PlanStep refStep = plan.getStep(stepId);
                if (refStep != null && refStep.getOutput() != null) {
                    Object resolved = resolvePath(refStep.getOutput(), path);
                    return resolved != null ? resolved : str; // 无法解析则保留原文
                }
                return str;
            }
        }
        return value;
    }

    /** 从某步骤 output 里按路径取值，路径形如 items[0].restaurantId 或 restaurantId */
    private Object resolvePath(Object root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (seg.isEmpty()) continue;
            cur = resolveSegment(cur, seg);
            if (cur == null) return null;
        }
        return cur;
    }

    /** 解析单个路径段：支持字段名 + [n] 数组索引 */
    private Object resolveSegment(Object cur, String seg) {
        String field = seg;
        String idxStr = null;
        int bracket = seg.indexOf('[');
        if (bracket >= 0) {
            field = seg.substring(0, bracket);
            int close = seg.indexOf(']', bracket);
            if (close > bracket) idxStr = seg.substring(bracket + 1, close);
        }
        Object target = cur;
        if (field != null && !field.isEmpty()) {
            if (target instanceof Map) {
                target = ((Map<?, ?>) target).get(field);
            } else {
                return null;
            }
        }
        if (idxStr != null && target instanceof List) {
            int idx;
            try {
                idx = Integer.parseInt(idxStr);
            } catch (NumberFormatException e) {
                return null;
            }
            List<?> list = (List<?>) target;
            target = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
        }
        return target;
    }

    private Map<String, Object> convertToMap(Object result) {
        if (result instanceof Map) return (Map<String, Object>) result;
        if (result instanceof List) {
            // 列表结果包装成 {"items": [...]}，便于占位符按索引引用
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("items", result);
            return wrapper;
        }
        try {
            return objectMapper.convertValue(result, Map.class);
        } catch (Exception e) {
            return Map.of("value", String.valueOf(result));
        }
    }
}
