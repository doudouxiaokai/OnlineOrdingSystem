package com.online_ordering_system.agent;

import java.util.List;

/**
 * 执行计划（Planner 的产出，可序列化）。
 * 是一个 DAG：steps 之间通过 dependsOn 表达依赖。
 * 说明：不使用 Lombok，显式写 getter/setter。
 */
public class Plan {
    private String runId;
    private String goal;           // 用户意图
    private List<PlanStep> steps;  // 步骤列表

    public PlanStep getStep(String stepId) {
        if (steps == null) return null;
        return steps.stream().filter(s -> s.getStepId().equals(stepId)).findFirst().orElse(null);
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }
}
