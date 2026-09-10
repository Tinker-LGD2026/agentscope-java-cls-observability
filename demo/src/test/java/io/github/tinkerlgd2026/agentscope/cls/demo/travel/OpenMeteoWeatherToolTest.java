package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class OpenMeteoWeatherToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GEOCODING =
            """
            {"results":[{"name":"上海","country":"中国","admin1":"上海","latitude":31.23,"longitude":121.47}]}
            """;
    private static final String FORECAST =
            """
            {
              "timezone":"Asia/Shanghai",
              "current":{"time":"2026-09-10T16:00","temperature_2m":27.4,"apparent_temperature":29.1,"relative_humidity_2m":73,"precipitation":0.2,"weather_code":61,"wind_speed_10m":12.5},
              "daily":{"time":["2026-09-11","2026-09-12"],"weather_code":[61,3],"temperature_2m_max":[29.0,30.5],"temperature_2m_min":[23.0,24.0],"precipitation_probability_max":[80,30]}
            }
            """;

    @Test
    void queriesEncodedCityAndReturnsOnlyUsefulWeatherFields() throws Exception {
        StubTransport transport = new StubTransport(response(200, GEOCODING), response(200, FORECAST));
        OpenMeteoWeatherTool tool = new OpenMeteoWeatherTool(JSON, transport);

        JsonNode result = JSON.readTree(tool.queryWeather("上海"));

        assertThat(transport.uris).hasSize(2);
        assertThat(transport.uris.get(0).getRawQuery()).contains("name=%E4%B8%8A%E6%B5%B7");
        assertThat(transport.uris.get(1).getQuery())
                .contains("latitude=31.23")
                .contains("longitude=121.47")
                .contains("timezone=auto");
        assertThat(transport.timeouts).allMatch(timeout -> !timeout.isNegative() && !timeout.isZero());
        assertThat(transport.timeouts).allMatch(timeout -> timeout.compareTo(Duration.ofSeconds(12)) <= 0);
        assertThat(result.get("location").get("name").asText()).isEqualTo("上海");
        assertThat(result.get("current").get("temperature_2m").asDouble()).isEqualTo(27.4);
        assertThat(result.get("daily")).hasSize(2);
        assertThat(result.toString()).doesNotContain("generationtime_ms");
    }

    @Test
    void rejectsBlankCityWithoutCallingTheNetwork() {
        StubTransport transport = new StubTransport();
        OpenMeteoWeatherTool tool = new OpenMeteoWeatherTool(JSON, transport);

        assertThatThrownBy(() -> tool.queryWeather("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city");
        assertThat(transport.uris).isEmpty();
    }

    @Test
    void reportsMissingLocationsSafely() {
        OpenMeteoWeatherTool tool =
                new OpenMeteoWeatherTool(JSON, new StubTransport(response(200, "{\"results\":[]}")));

        assertThatThrownBy(() -> tool.queryWeather("不存在的城市"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("city was not found")
                .hasMessageNotContaining("不存在的城市");
    }

    @Test
    void doesNotRetryClientErrorsOrExposeResponseBodies() {
        StubTransport transport =
                new StubTransport(response(400, "secret upstream diagnostic and request content"));
        OpenMeteoWeatherTool tool = new OpenMeteoWeatherTool(JSON, transport);

        assertThatThrownBy(() -> tool.queryWeather("上海"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("secret upstream");
        assertThat(transport.uris).hasSize(1);
    }

    @Test
    void retriesOneTransientResponse() throws Exception {
        StubTransport transport =
                new StubTransport(
                        response(503, "temporary details"),
                        response(200, GEOCODING),
                        response(200, FORECAST));
        OpenMeteoWeatherTool tool = new OpenMeteoWeatherTool(JSON, transport);

        JsonNode result = JSON.readTree(tool.queryWeather("上海"));

        assertThat(transport.uris).hasSize(3);
        assertThat(result.get("timezone").asText()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void weatherClientNeverFollowsServerRedirects() {
        assertThat(OpenMeteoWeatherTool.newHttpClient().followRedirects())
                .isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void rejectsOversizedHttpBodiesBeforeStringAllocation() throws Exception {
        byte[] allowed = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(OpenMeteoWeatherTool.readBoundedBody(new ByteArrayInputStream(allowed)))
                .isEqualTo("ok");

        byte[] oversized = new byte[1024 * 1024 + 1];
        assertThatThrownBy(
                        () ->
                                OpenMeteoWeatherTool.readBoundedBody(
                                        new ByteArrayInputStream(oversized)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("safe size limit");
    }

    @Test
    void convertsTransportFailureToSafeToolErrorAndRestoresInterrupt() {
        OpenMeteoWeatherTool ioFailure =
                new OpenMeteoWeatherTool(
                        JSON,
                        (uri, timeout) -> {
                            throw new IOException("token=do-not-expose");
                        });
        assertThatThrownBy(() -> ioFailure.queryWeather("上海"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("weather service unavailable")
                .hasMessageNotContaining("do-not-expose");

        OpenMeteoWeatherTool interrupted =
                new OpenMeteoWeatherTool(
                        JSON,
                        (uri, timeout) -> {
                            throw new InterruptedException("interrupted");
                        });
        assertThatThrownBy(() -> interrupted.queryWeather("上海"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("weather request interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }

    private static OpenMeteoWeatherTool.WeatherHttpResponse response(int status, String body) {
        return new OpenMeteoWeatherTool.WeatherHttpResponse(status, body);
    }

    private static final class StubTransport implements OpenMeteoWeatherTool.WeatherHttpTransport {
        private final Queue<OpenMeteoWeatherTool.WeatherHttpResponse> responses = new ArrayDeque<>();
        private final List<URI> uris = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();

        private StubTransport(OpenMeteoWeatherTool.WeatherHttpResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public OpenMeteoWeatherTool.WeatherHttpResponse get(URI uri, Duration timeout) {
            uris.add(uri);
            timeouts.add(timeout);
            if (responses.isEmpty()) {
                throw new AssertionError("unexpected HTTP call");
            }
            return responses.remove();
        }
    }
}
