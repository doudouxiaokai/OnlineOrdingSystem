package com.online_ordering_system.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 Agent 可调用的工具。
 * 方法签名约定：接收一个 Map<String,Object> 参数（LLM 传入的 arguments），返回任意对象。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /** 工具名（LLM 可见，与 prompt 中的工具名一致） */
    String name();

    /** 工具描述（喂给 LLM 的 function description） */
    String description();

    /** 副作用级别：READ=只读 / WRITE=写但可回滚 / COMMIT=不可逆（下单/支付） */
    SideEffect sideEffect() default SideEffect.READ;

    /** 是否需要人工确认（如大额下单） */
    boolean confirmRequired() default false;

    /** 参数 JSON Schema（可选，缺省时用空 object） */
    String parametersJson() default "{\"type\":\"object\",\"properties\":{},\"required\":[]}";

    enum SideEffect { READ, WRITE, COMMIT }
}
