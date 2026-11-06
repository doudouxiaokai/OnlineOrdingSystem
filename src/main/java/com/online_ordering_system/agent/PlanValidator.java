package com.online_ordering_system.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 计划校验器：校验 Planner 产出的计划合法性，非法时尝试修复。
 * 校验项：工具存在性、依赖无环、依赖引用存在、stepId 唯一。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanValidator {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 校验并解析计划 JSON。合法返回 Plan，非法返回 null。
     */
    public Plan validateAndRepair(String planJson) {
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.size() == 0) {
                return null;
            }

            Plan plan = new Plan();
            List<PlanStep> steps = new ArrayList<>();
            Set<String> stepIds = new HashSet<>();

            for (JsonNode stepNode : stepsNode) {
                PlanStep step = new PlanStep();
                String stepId = stepNode.path("stepId").asText();
                String tool = stepNode.path("tool").asText();

                // 校验1：工具存在
                if (!toolRegistry.contains(tool)) {
                    log.warn("计划含未知工具：{}", tool);
                    return null;
                }
                // 校验2：stepId 唯一且非空
                if (stepId.isEmpty() || stepIds.contains(stepId)) {
                    log.warn("stepId 非法或重复：{}", stepId);
                    return null;
                }
                stepIds.add(stepId);

                step.setStepId(stepId);
                step.setTool(tool);
                step.setExpect(stepNode.path("expect").asText(""));

                // 解析 args
                Map<String, Object> args = new HashMap<>();
                JsonNode argsNode = stepNode.get("args");
                if (argsNode != null && argsNode.isObject()) {
                    args = objectMapper.convertValue(argsNode, Map.class);
                }
                step.setArgs(args);

                // 解析 dependsOn
                List<String> dependsOn = new ArrayList<>();
                JsonNode depNode = stepNode.get("dependsOn");
                if (depNode != null && depNode.isArray()) {
                    for (JsonNode d : depNode) {
                        dependsOn.add(d.asText());
                    }
                }
                step.setDependsOn(dependsOn);
                step.setStatus("PENDING");

                steps.add(step);
            }

            // 校验3：依赖引用必须存在
            for (PlanStep step : steps) {
                for (String dep : step.getDependsOn()) {
                    if (!stepIds.contains(dep)) {
                        log.warn("步骤 {} 依赖不存在的步骤 {}", step.getStepId(), dep);
                        return null;
                    }
                }
            }

            // 校验4：无环（拓扑排序）
            if (hasCycle(steps)) {
                log.warn("计划存在循环依赖");
                return null;
            }

            plan.setSteps(steps);
            return plan;
        } catch (Exception e) {
            log.warn("计划解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 检测依赖环 */
    private boolean hasCycle(List<PlanStep> steps) {
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (PlanStep s : steps) {
            graph.put(s.getStepId(), s.getDependsOn() == null ? new ArrayList<>() : s.getDependsOn());
            indegree.put(s.getStepId(), 0);
        }
        // 这里 dependsOn 是「依赖谁」，反向建边算入度
        Map<String, List<String>> dependents = new HashMap<>();
        for (PlanStep s : steps) {
            dependents.putIfAbsent(s.getStepId(), new ArrayList<>());
            for (String dep : s.getDependsOn() == null ? Collections.<String>emptyList() : s.getDependsOn()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.getStepId());
                indegree.merge(s.getStepId(), 1, Integer::sum);
            }
        }
        Queue<String> queue = new LinkedList<>();
        indegree.forEach((id, deg) -> { if (deg == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : dependents.getOrDefault(cur, new ArrayList<>())) {
                indegree.merge(next, -1, Integer::sum);
                if (indegree.get(next) == 0) queue.add(next);
            }
        }
        return visited != steps.size();
    }
}
