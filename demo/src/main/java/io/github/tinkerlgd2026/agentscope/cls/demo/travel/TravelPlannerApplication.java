package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.github.tinkerlgd2026.agentscope.cls.ClsAgentObservability;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TravelPlannerApplication {
    private static final Duration AGENT_TIMEOUT =
            Objects.requireNonNull(Duration.ofMinutes(4));
    private static final Duration FLUSH_TIMEOUT =
            Objects.requireNonNull(Duration.ofSeconds(30));

    private TravelPlannerApplication() {}

    public static void main(String[] args) {
        Map<String, String> environment =
                Map.copyOf(Objects.requireNonNull(System.getenv()));
        TravelDemoSettings settings =
                TravelDemoSettings.fromEnvironment(Objects.requireNonNull(environment));
        ClsObservabilityConfig clsConfig =
                ClsObservabilityConfig.fromEnvironment(Objects.requireNonNull(environment));

        try (ModelResources modelResources = createModel(settings.deepSeekApiKey());
                ClsAgentObservability observability = ClsAgentObservability.create(clsConfig)) {
            clsConfig.destroyCredentials();
            OpenMeteoWeatherTool weatherTool = new OpenMeteoWeatherTool();
            try (ReActAgent planner =
                    createPlannerAgent(modelResources.model(), observability, weatherTool)) {
                String first =
                        runTurn(
                                planner,
                                settings.newRuntimeContext(),
                                settings.userName(),
                                settings.firstPrompt(),
                                AGENT_TIMEOUT);
                System.out.println("\n=== 第一轮旅行方案 ===\n" + first);

                String second =
                        runTurn(
                                planner,
                                settings.newRuntimeContext(),
                                settings.userName(),
                                settings.secondPrompt(),
                                AGENT_TIMEOUT);
                System.out.println("\n=== 第二轮调整方案 ===\n" + second);
            }
            if (!observability.flush(Objects.requireNonNull(FLUSH_TIMEOUT))) {
                System.err.println(
                        "TRAVEL_TELEMETRY_WARNING=flush did not complete; business answers are unchanged");
            }
            System.err.println("TRAVEL_SESSION_ID=" + settings.sessionId());
            System.err.println("TRAVEL_TELEMETRY=" + observability.snapshot());
        } finally {
            reactor.core.scheduler.Schedulers.shutdownNow();
        }
    }

    static Toolkit createPlannerToolkit(
            Model model,
            ClsAgentObservability observability,
            OpenMeteoWeatherTool weatherTool) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(observability, "observability");
        Objects.requireNonNull(weatherTool, "weatherTool");
        Toolkit toolkit = new Toolkit();
        toolkit.registration()
                .subAgent(
                        () -> createWeatherAgent(model, observability, weatherTool),
                        SubAgentConfig.builder()
                                .toolName("ask_weather_expert")
                                .description(
                                        "Ask a weather expert to query real weather and explain how"
                                                + " it affects a travel plan. Pass the city, dates"
                                                + " and traveler needs in message.")
                                .forwardEvents(false)
                                .build())
                .apply();
        toolkit.registration()
                .subAgent(
                        () -> createItineraryAgent(model, observability),
                        SubAgentConfig.builder()
                                .toolName("ask_itinerary_expert")
                                .description(
                                        "Ask an itinerary expert to design a practical day-by-day"
                                                + " route. Pass the city, dates, preferences and"
                                                + " weather summary in message.")
                                .forwardEvents(false)
                                .build())
                .apply();
        toolkit.registerTool(new TravelBudgetTools());
        return toolkit;
    }

    static ReActAgent createPlannerAgent(
            Model model,
            ClsAgentObservability observability,
            OpenMeteoWeatherTool weatherTool) {
        return ReActAgent.builder()
                .name("travel-planner")
                .description("Coordinate weather, itinerary and budget specialists")
                .sysPrompt(
                        "你是旅行规划主 Agent。回答旅行规划问题前，必须调用"
                                + " ask_weather_expert 获取实时天气，再调用"
                                + " ask_itinerary_expert 生成路线，并调用 calculate_budget"
                                + " 计算费用。把天气摘要完整传给行程专家。工具失败时明确说明"
                                + " 哪项实时信息不可用，并继续给出安全的基础方案。最终使用中文，"
                                + " 清晰列出每日行程、天气建议、费用明细和总预算。")
                .model(model)
                .toolkit(createPlannerToolkit(model, observability, weatherTool))
                .middleware(observability.middleware())
                .maxIters(12)
                .build();
    }

    static ReActAgent createWeatherAgent(
            Model model,
            ClsAgentObservability observability,
            OpenMeteoWeatherTool weatherTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        return ReActAgent.builder()
                .name("weather-expert")
                .description("Query and interpret real weather for travel planning")
                .sysPrompt(
                        "你是天气专家。必须调用 query_weather 查询真实 Open-Meteo 数据，"
                                + "不能凭空编造天气。根据查询结果简要说明温度、降水、风力、"
                                + "穿衣和室内外活动建议。工具失败时如实报告不可用。")
                .model(model)
                .toolkit(toolkit)
                .middleware(observability.middleware())
                .maxIters(6)
                .build();
    }

    static ReActAgent createItineraryAgent(
            Model model, ClsAgentObservability observability) {
        return ReActAgent.builder()
                .name("itinerary-expert")
                .description("Design practical day-by-day city itineraries")
                .sysPrompt(
                        "你是行程专家。根据主 Agent 提供的城市、日期、人数、偏好和天气摘要，"
                                + "生成紧凑可执行的每日路线。不要声称自己查询了实时天气；"
                                + "只使用消息中提供的天气信息。")
                .model(model)
                .middleware(observability.middleware())
                .maxIters(4)
                .build();
    }

    static String runTurn(
            ReActAgent planner,
            RuntimeContext context,
            String userName,
            String prompt,
            Duration timeout) {
        if (planner == null || context == null || timeout == null) {
            throw new IllegalArgumentException("planner, context and timeout are required");
        }
        Msg request =
                Msg.builder()
                        .name(userName)
                        .role(MsgRole.USER)
                        .textContent(prompt)
                        .build();
        Msg response = planner.call(List.of(request), context).block(timeout);
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("travel planner returned no response");
        }
        return response.getTextContent();
    }

    private static ModelResources createModel(String apiKey) {
        HttpTransport transport = JdkHttpTransport.builder().build();
        try {
            Model model =
                    OpenAIChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl("https://api.deepseek.com/v1")
                            .modelName("deepseek-chat")
                            .httpTransport(transport)
                            .stream(true)
                            .nativeStructuredOutputWithTools(false)
                            .build();
            return new ModelResources(model, transport);
        } catch (RuntimeException exception) {
            transport.close();
            throw exception;
        }
    }

    private record ModelResources(Model model, HttpTransport transport) implements AutoCloseable {
        private ModelResources {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(transport, "transport");
        }

        @Override
        public void close() {
            transport.close();
        }
    }
}
