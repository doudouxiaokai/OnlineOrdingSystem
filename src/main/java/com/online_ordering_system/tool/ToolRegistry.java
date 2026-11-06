package com.online_ordering_system.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册中心。
 * 启动时扫描所有 Spring Bean 定义（不提前实例化 Bean，避免与 MyBatis Mapper
 * 形成循环依赖），收集标注了 @AgentTool 的方法，统一生成 OpenAI function calling 工具定义。
 */
@Component
@Slf4j
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** name -> ToolDefinition（bean 延迟到首次调用时获取） */
    private final Map<String, ToolDefinition> tools = new HashMap<>();

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void scanTools() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        ConfigurableListableBeanFactory beanFactory =
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        for (String beanName : beanNames) {
            BeanDefinition bd;
            try {
                bd = beanFactory.getBeanDefinition(beanName);
            } catch (Exception e) {
                continue;
            }
            String className = bd.getBeanClassName();
            if (className == null) continue;

            Class<?> beanClass;
            try {
                beanClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue;
            }

            for (Method method : beanClass.getDeclaredMethods()) {
                AgentTool annotation = method.getAnnotation(AgentTool.class);
                if (annotation == null) continue;

                ToolDefinition def = new ToolDefinition();
                def.name = annotation.name();
                def.description = annotation.description();
                def.sideEffect = annotation.sideEffect();
                def.confirmRequired = annotation.confirmRequired();
                def.parametersJson = annotation.parametersJson();
                def.beanName = beanName;
                def.beanClass = beanClass;
                def.method = method;

                tools.put(def.name, def);
                log.info("注册 Agent 工具：{}（副作用：{}）", def.name, def.sideEffect);
            }
        }
        log.info("共注册 {} 个 Agent 工具", tools.size());
    }

    /** 获取某个工具定义 */
    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /** 是否包含某工具 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 生成 OpenAI function calling 的工具列表 */
    public List<Map<String, Object>> toOpenAiTools() {
        return tools.values().stream().map(def -> {
            Map<String, Object> function = new HashMap<>();
            function.put("name", def.name);
            function.put("description", def.description);
            try {
                function.put("parameters", objectMapper.readTree(def.parametersJson));
            } catch (Exception e) {
                function.put("parameters", objectMapper.createObjectNode());
            }
            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            return tool;
        }).collect(Collectors.toList());
    }

    /**
     * 执行工具。arguments 是 LLM 传入的 JSON 对象（已反序列化为 Map）。
     * bean 延迟到此时才从容器获取，避免启动期循环依赖。
     */
    public Object invoke(String name, Map<String, Object> arguments) throws Exception {
        ToolDefinition def = tools.get(name);
        if (def == null) {
            throw new RuntimeException("未知工具：" + name);
        }
        Object bean = applicationContext.getBean(def.beanName);
        // 约定工具方法签名：public Xxx method(Map<String,Object> args)
        return def.method.invoke(bean, arguments);
    }

    /** 工具定义 */
    public static class ToolDefinition {
        public String name;
        public String description;
        public AgentTool.SideEffect sideEffect;
        public boolean confirmRequired;
        public String parametersJson;
        public String beanName;
        public Class<?> beanClass;
        public Method method;
    }
}
