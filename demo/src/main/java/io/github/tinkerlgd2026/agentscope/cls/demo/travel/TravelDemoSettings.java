package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import io.agentscope.core.agent.RuntimeContext;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import java.util.Map;
import java.util.UUID;

final class TravelDemoSettings {
    private static final String DEFAULT_USER_ID = "travel-demo-user";
    private static final String DEFAULT_USER_NAME = "Travel Demo User";
    private static final String DEFAULT_CITY = "上海";

    private final String deepSeekApiKey;
    private final String sessionId;
    private final String userId;
    private final String userName;
    private final String city;
    private final String firstPrompt;
    private final String secondPrompt;
    private final boolean reasoningEnabled;

    private TravelDemoSettings(
            String deepSeekApiKey,
            String sessionId,
            String userId,
            String userName,
            String city,
            String firstPrompt,
            String secondPrompt,
            boolean reasoningEnabled) {
        this.deepSeekApiKey = deepSeekApiKey;
        this.sessionId = sessionId;
        this.userId = userId;
        this.userName = userName;
        this.city = city;
        this.firstPrompt = firstPrompt;
        this.secondPrompt = secondPrompt;
        this.reasoningEnabled = reasoningEnabled;
    }

    static TravelDemoSettings fromEnvironment(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment is required");
        }
        String apiKey = required(environment, "DEEPSEEK_API_KEY");
        String sessionId =
                optional(environment, "TRAVEL_SESSION_ID", "travel-" + UUID.randomUUID());
        String userId = optional(environment, "TRAVEL_USER_ID", DEFAULT_USER_ID);
        String userName = optional(environment, "TRAVEL_USER_NAME", DEFAULT_USER_NAME);
        String city = optional(environment, "TRAVEL_CITY", DEFAULT_CITY);
        String firstPrompt =
                optional(
                        environment,
                        "TRAVEL_PROMPT",
                        "请为两位成年人规划"
                                + city
                                + "两日游。必须先咨询天气专家和行程专家，再调用预算工具计算总预算；"
                                + "总预算目标为3000元，请给出每天的安排、天气建议和分类费用。");
        String secondPrompt =
                optional(
                        environment,
                        "TRAVEL_FOLLOW_UP_PROMPT",
                        "如果第二天下雨，请把室外安排替换为室内活动，并把总预算目标提高到4000元。"
                                + "请继续使用天气专家、行程专家和预算工具后再回答。");
        boolean reasoningEnabled =
                strictBoolean(environment.get("TRAVEL_ENABLE_REASONING"), "TRAVEL_ENABLE_REASONING");
        return new TravelDemoSettings(
                apiKey,
                sessionId,
                userId,
                userName,
                city,
                firstPrompt,
                secondPrompt,
                reasoningEnabled);
    }

    RuntimeContext newRuntimeContext() {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .put(
                        ClsInvocationContext.class,
                        new ClsInvocationContext(
                                userName, null, "travel-planner", "java-sdk"))
                .build();
    }

    String deepSeekApiKey() {
        return deepSeekApiKey;
    }

    String sessionId() {
        return sessionId;
    }

    String userId() {
        return userId;
    }

    String userName() {
        return userName;
    }

    String city() {
        return city;
    }

    String firstPrompt() {
        return firstPrompt;
    }

    String secondPrompt() {
        return secondPrompt;
    }

    boolean reasoningEnabled() {
        return reasoningEnabled;
    }

    private static boolean strictBoolean(String value, String name) {
        if (value == null) {
            return false;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String optional(
            Map<String, String> environment, String name, String defaultValue) {
        String value = environment.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
