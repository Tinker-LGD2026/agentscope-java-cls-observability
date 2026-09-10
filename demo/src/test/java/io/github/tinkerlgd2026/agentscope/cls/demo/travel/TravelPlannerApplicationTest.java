package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.github.tinkerlgd2026.agentscope.cls.ClsAgentObservability;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.transport.InMemorySpanSink;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class TravelPlannerApplicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void registersTwoSubAgentsAndBudgetTool() {
        Model model = new StaticModel();
        OpenMeteoWeatherTool weather =
                new OpenMeteoWeatherTool(
                        JSON,
                        (uri, timeout) ->
                                new OpenMeteoWeatherTool.WeatherHttpResponse(500, "ignored"));
        try (ClsAgentObservability observability =
                ClsAgentObservability.create(
                        ClsObservabilityConfig.builder().build(), new InMemorySpanSink())) {
            Toolkit toolkit =
                    TravelPlannerApplication.createPlannerToolkit(model, observability, weather);

            assertThat(toolkit.getToolNames())
                    .containsExactlyInAnyOrder(
                            "ask_weather_expert", "ask_itinerary_expert", "calculate_budget");
        }
    }

    @Test
    void calculatesAStableBudgetBreakdown() throws Exception {
        JsonNode result =
                JSON.readTree(
                        new TravelBudgetTools()
                                .calculateBudget(2, 2, 1, 600, 150, 300, 400));

        assertThat(result.get("lodging").asLong()).isEqualTo(600);
        assertThat(result.get("food").asLong()).isEqualTo(600);
        assertThat(result.get("local_transport").asLong()).isEqualTo(300);
        assertThat(result.get("tickets").asLong()).isEqualTo(400);
        assertThat(result.get("total").asLong()).isEqualTo(1900);
    }

    @Test
    void createsFreshContextsForTheSameCustomerSession() {
        TravelDemoSettings settings =
                TravelDemoSettings.fromEnvironment(
                        Map.of(
                                "DEEPSEEK_API_KEY", "model-key",
                                "TRAVEL_SESSION_ID", "travel-session-1",
                                "TRAVEL_USER_ID", "customer-1"));

        RuntimeContext first = settings.newRuntimeContext();
        RuntimeContext second = settings.newRuntimeContext();

        assertThat(first).isNotSameAs(second);
        assertThat(first.getSessionId()).isEqualTo(second.getSessionId());
        assertThat(first.getUserId()).isEqualTo(second.getUserId());
    }

    @Test
    void weatherFailureDoesNotPreventThePlannerFromReturningABasePlan() {
        InMemorySpanSink sink = new InMemorySpanSink();
        SubAgentScriptedModel model = new SubAgentScriptedModel();
        OpenMeteoWeatherTool failingWeather =
                new OpenMeteoWeatherTool(
                        JSON,
                        (uri, timeout) -> {
                            throw new java.io.IOException("upstream unavailable");
                        });
        TravelDemoSettings settings =
                TravelDemoSettings.fromEnvironment(
                        Map.of(
                                "DEEPSEEK_API_KEY", "model-key",
                                "TRAVEL_SESSION_ID", "travel-fallback-session",
                                "TRAVEL_USER_ID", "customer-1"));

        try (ClsAgentObservability observability =
                        ClsAgentObservability.create(
                                ClsObservabilityConfig.builder().build(), sink);
                io.agentscope.core.ReActAgent planner =
                        TravelPlannerApplication.createPlannerAgent(
                                model, observability, failingWeather)) {
            String reply =
                    TravelPlannerApplication.runTurn(
                            planner,
                            settings.newRuntimeContext(),
                            settings.userName(),
                            "请规划一次旅行",
                            Duration.ofSeconds(10));
            assertThat(reply).isEqualTo("父 Agent 已整合全部建议。");
            assertThat(observability.flush(Duration.ofSeconds(5))).isTrue();
        }

        List<ClsSpanRecord> weatherSpans =
                sink.records().stream()
                        .filter(
                                record -> {
                                    try {
                                        return "query_weather"
                                                .equals(
                                                        JSON.readTree(record.attribute())
                                                                .path("gen_ai.tool.name")
                                                                .asText());
                                    } catch (Exception exception) {
                                        throw new IllegalArgumentException(exception);
                                    }
                                })
                        .toList();
        assertThat(weatherSpans).singleElement().satisfies(record ->
                assertThat(record.statusCode()).isEqualTo("ERROR"));
    }

    @Test
    void nativeSubAgentSharesTheParentTraceAndIdentity() throws Exception {
        InMemorySpanSink sink = new InMemorySpanSink();
        SubAgentScriptedModel model = new SubAgentScriptedModel();
        OpenMeteoWeatherTool weather =
                new OpenMeteoWeatherTool(JSON, TravelPlannerApplicationTest::weatherResponse);
        TravelDemoSettings settings =
                TravelDemoSettings.fromEnvironment(
                        Map.of(
                                "DEEPSEEK_API_KEY", "model-key",
                                "TRAVEL_SESSION_ID", "travel-session-1",
                                "TRAVEL_USER_ID", "customer-1"));

        String firstReply;
        String secondReply;
        try (ClsAgentObservability observability =
                        ClsAgentObservability.create(
                                ClsObservabilityConfig.builder().build(), sink);
                io.agentscope.core.ReActAgent planner =
                        TravelPlannerApplication.createPlannerAgent(model, observability, weather)) {
            firstReply =
                    TravelPlannerApplication.runTurn(
                            planner,
                            settings.newRuntimeContext(),
                            settings.userName(),
                            "请规划一次旅行",
                            Duration.ofSeconds(10));
            secondReply =
                    TravelPlannerApplication.runTurn(
                            planner,
                            settings.newRuntimeContext(),
                            settings.userName(),
                            "请按雨天调整旅行",
                            Duration.ofSeconds(10));
            assertThat(observability.flush(Duration.ofSeconds(5))).isTrue();
        }

        List<ClsSpanRecord> records = sink.records();
        assertThat(model.parentCallCount()).isEqualTo(8);
        assertThat(model.weatherCallCount()).isEqualTo(4);
        assertThat(model.itineraryCallCount()).isEqualTo(2);
        assertThat(firstReply).isEqualTo("父 Agent 已整合全部建议。");
        assertThat(secondReply).isEqualTo("父 Agent 已整合全部建议。");
        List<Map.Entry<ClsSpanRecord, JsonNode>> decoded =
                records.stream()
                        .map(
                                record -> {
                                    try {
                                        return Map.entry(record, JSON.readTree(record.attribute()));
                                    } catch (Exception exception) {
                                        throw new IllegalArgumentException(exception);
                                    }
                                })
                        .toList();
        List<Map.Entry<ClsSpanRecord, JsonNode>> agents =
                decoded.stream()
                        .filter(entry -> "agent".equals(entry.getValue().path("gen_ai.span.kind").asText()))
                        .toList();
        assertThat(agents).hasSize(6);
        assertThat(
                        agents.stream()
                                .map(entry -> entry.getValue().path("gen_ai.agent.name").asText())
                                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "travel-planner", "weather-expert", "itinerary-expert");
        assertThat(records.stream().map(ClsSpanRecord::traceID).collect(Collectors.toSet()))
                .hasSize(2);
        assertThat(
                        decoded.stream()
                                .filter(
                                        entry ->
                                                "tool".equals(
                                                        entry.getValue()
                                                                .path("gen_ai.span.kind")
                                                                .asText()))
                                .map(entry -> entry.getValue().path("gen_ai.tool.name").asText())
                                .collect(Collectors.toSet()))
                .contains(
                        "ask_weather_expert",
                        "query_weather",
                        "ask_itinerary_expert",
                        "calculate_budget");
        assertThat(decoded)
                .allSatisfy(
                        entry -> {
                            assertThat(entry.getValue().path("gen_ai.session.id").asText())
                                    .isEqualTo("travel-session-1");
                            assertThat(entry.getValue().path("gen_ai.user.id").asText())
                                    .isEqualTo("customer-1");
                            assertThat(entry.getValue().path("gen_ai.turn.id").asText()).isNotBlank();
                        });
        Set<String> spanIds = records.stream().map(ClsSpanRecord::spanID).collect(Collectors.toSet());
        assertThat(records)
                .allSatisfy(
                        record -> {
                            if (!record.parentSpanID().isEmpty()) {
                                assertThat(spanIds).contains(record.parentSpanID());
                            }
                        });
        Set<String> turns =
                decoded.stream()
                        .map(entry -> entry.getValue().path("gen_ai.turn.id").asText())
                        .collect(Collectors.toSet());
        assertThat(turns).hasSize(2);
    }

    private static OpenMeteoWeatherTool.WeatherHttpResponse weatherResponse(
            java.net.URI uri, Duration timeout) {
        if (uri.getHost().startsWith("geocoding-api")) {
            return new OpenMeteoWeatherTool.WeatherHttpResponse(
                    200,
                    "{\"results\":[{\"name\":\"上海\",\"country\":\"中国\","
                            + "\"admin1\":\"上海\",\"latitude\":31.23,"
                            + "\"longitude\":121.47}]}");
        }
        return new OpenMeteoWeatherTool.WeatherHttpResponse(
                200,
                "{\"timezone\":\"Asia/Shanghai\",\"current\":{"
                        + "\"time\":\"2026-09-10T16:00\",\"temperature_2m\":27.4,"
                        + "\"weather_code\":61},\"daily\":{\"time\":[\"2026-09-11\"],"
                        + "\"weather_code\":[61],\"temperature_2m_max\":[29],"
                        + "\"temperature_2m_min\":[23],"
                        + "\"precipitation_probability_max\":[80]}}");
    }

    private static final class StaticModel implements Model {
        @Override
        public String getModelName() {
            return "static-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return Flux.just(textResponse("response-1", "done"));
        }
    }

    private static final class SubAgentScriptedModel implements Model {
        private final AtomicInteger parentCalls = new AtomicInteger();
        private final AtomicInteger weatherCalls = new AtomicInteger();
        private final AtomicInteger itineraryCalls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "sub-agent-scripted-model";
        }

        private int parentCallCount() {
            return parentCalls.get();
        }

        private int weatherCallCount() {
            return weatherCalls.get();
        }

        private int itineraryCallCount() {
            return itineraryCalls.get();
        }

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            Set<String> names = tools.stream().map(ToolSchema::getName).collect(Collectors.toSet());
            if (names.contains("query_weather")) {
                int call = weatherCalls.incrementAndGet();
                return Flux.just(
                        call % 2 == 1
                                ? toolCallResponse(
                                        "weather-tool-" + call,
                                        "call-weather-" + call,
                                        "query_weather",
                                        Map.of("city", "上海"),
                                        "{\"city\":\"上海\"}")
                                : textResponse("weather-answer-" + call, "真实天气：有雨。"));
            }
            if (!names.contains("ask_weather_expert")) {
                itineraryCalls.incrementAndGet();
                return Flux.just(textResponse("itinerary-answer", "室内外结合的两日行程。"));
            }

            int call = parentCalls.incrementAndGet();
            return Flux.just(
                    switch ((call - 1) % 4) {
                        case 0 ->
                                toolCallResponse(
                                        "parent-weather-" + call,
                                        "call-weather-expert-" + call,
                                        "ask_weather_expert",
                                        Map.of("message", "查询上海旅行天气"),
                                        "{\"message\":\"查询上海旅行天气\"}");
                        case 1 ->
                                toolCallResponse(
                                        "parent-itinerary-" + call,
                                        "call-itinerary-" + call,
                                        "ask_itinerary_expert",
                                        Map.of("message", "根据有雨天气安排上海两日行程"),
                                        "{\"message\":\"根据有雨天气安排上海两日行程\"}");
                        case 2 ->
                                toolCallResponse(
                                        "parent-budget-" + call,
                                        "call-budget-" + call,
                                        "calculate_budget",
                                        Map.of(
                                                "travelers", 2,
                                                "days", 2,
                                                "hotel_nights", 1,
                                                "hotel_per_night", 600,
                                                "food_per_person_per_day", 150,
                                                "local_transport_total", 300,
                                                "tickets_total", 400),
                                        "{\"travelers\":2,\"days\":2,\"hotel_nights\":1,"
                                                + "\"hotel_per_night\":600,"
                                                + "\"food_per_person_per_day\":150,"
                                                + "\"local_transport_total\":300,"
                                                + "\"tickets_total\":400}");
                        default -> textResponse("parent-answer-" + call, "父 Agent 已整合全部建议。");
                    });
        }
    }

    private static ChatResponse toolCallResponse(
            String responseId,
            String callId,
            String name,
            Map<String, Object> input,
            String content) {
        return ChatResponse.builder()
                .id(responseId)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(callId)
                                        .name(name)
                                        .input(input)
                                        .content(content)
                                        .build()))
                .usage(new ChatUsage(4, 2, 0.01))
                .metadata(Map.of("provider", "local"))
                .finishReason("tool_calls")
                .build();
    }

    private static ChatResponse textResponse(String id, String text) {
        return ChatResponse.builder()
                .id(id)
                .content(
                        List.of(
                                io.agentscope.core.message.TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(4, 2, 0.01))
                .metadata(Map.of("provider", "local"))
                .finishReason("stop")
                .build();
    }
}
