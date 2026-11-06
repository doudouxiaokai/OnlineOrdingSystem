package com.online_ordering_system.agent;

import java.util.List;
import java.util.Map;

/**
 * 计划中的一个步骤（Plan 的组成单元）。
 * 字段与 Planner 输出的 JSON 对应。
 * 说明：不使用 Lombok，显式写 getter/setter，避免注解处理器在命令行编译下不生效。
 */
public class PlanStep {
    private String stepId;          // 步骤唯一标识，如 s1/s2
    private String tool;            // 工具名
    private Map<String, Object> args; // 入参（可能含 ${stepId.field} 占位符）
    private List<String> dependsOn;   // 依赖的前置步骤
    private String expect;          // 断言描述（执行后校验）
    private String status;          // PENDING/RUNNING/DONE/FAILED/SKIPPED
    private Map<String, Object> output; // 该步骤执行结果（供后续步骤引用）

    /** 该步骤是否所有依赖已完成 */
    public boolean depsResolved(List<PlanStep> allSteps) {
        if (dependsOn == null || dependsOn.isEmpty()) return true;
        for (String depId : dependsOn) {
            boolean found = allSteps.stream()
                    .anyMatch(s -> s.getStepId().equals(depId) && "DONE".equals(s.getStatus()));
            if (!found) return false;
        }
        return true;
    }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> args) { this.args = args; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
    public String getExpect() { return expect; }
    public void setExpect(String expect) { this.expect = expect; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getOutput() { return output; }
    public void setOutput(Map<String, Object> output) { this.output = output; }
}
