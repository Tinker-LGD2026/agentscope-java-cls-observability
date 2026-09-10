package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpenMeteoWeatherTool {
    private static final URI GEOCODING_ENDPOINT =
            URI.create("https://geocoding-api.open-meteo.com/v1/search");
    private static final URI FORECAST_ENDPOINT =
            URI.create("https://api.open-meteo.com/v1/forecast");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final ObjectMapper json;
    private final WeatherHttpTransport transport;

    public OpenMeteoWeatherTool() {
        this(new ObjectMapper(), new JdkWeatherHttpTransport());
    }

    OpenMeteoWeatherTool(ObjectMapper json, WeatherHttpTransport transport) {
        if (json == null || transport == null) {
            throw new IllegalArgumentException("json and transport are required");
        }
        this.json = json;
        this.transport = transport;
    }

    @Tool(
            name = "query_weather",
            description =
                    "Query real current and daily weather for a city. Always use this before"
                            + " making weather-dependent travel recommendations.")
    public String queryWeather(
            @ToolParam(name = "city", description = "City name, for example 上海 or Beijing")
                    String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city is required");
        }
        long deadline = System.nanoTime() + TOOL_TIMEOUT.toNanos();
        try {
            Location location = resolveLocation(city.trim(), deadline);
            JsonNode forecast = requestJson(forecastUri(location), deadline);
            return json.writeValueAsString(toResult(location, forecast));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("weather request interrupted");
        } catch (IOException exception) {
            throw new IllegalStateException("weather service unavailable");
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw exception;
            }
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("weather response could not be processed");
        }
    }

    private Location resolveLocation(String city, long deadline)
            throws IOException, InterruptedException {
        URI uri =
                URI.create(
                        GEOCODING_ENDPOINT
                                + "?name="
                                + encodeQuery(city)
                                + "&count=1&language=zh&format=json");
        JsonNode results = requestJson(uri, deadline).path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("city was not found by weather service");
        }
        JsonNode first = results.get(0);
        if (!first.path("latitude").isNumber() || !first.path("longitude").isNumber()) {
            throw new IllegalStateException("weather location response is invalid");
        }
        return new Location(
                first.path("name").asText(""),
                first.path("country").asText(""),
                first.path("admin1").asText(""),
                first.path("latitude").asDouble(),
                first.path("longitude").asDouble());
    }

    private URI forecastUri(Location location) {
        return URI.create(
                FORECAST_ENDPOINT
                        + "?latitude="
                        + location.latitude()
                        + "&longitude="
                        + location.longitude()
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                        + "precipitation,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                        + "precipitation_probability_max"
                        + "&forecast_days=3&timezone=auto");
    }

    private JsonNode requestJson(URI uri, long deadline) throws IOException, InterruptedException {
        WeatherHttpResponse response = null;
        IOException transportFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                response = transport.get(uri, remaining(deadline));
                if (response.statusCode() == 200) {
                    try {
                        return json.readTree(response.body());
                    } catch (IOException exception) {
                        throw new IllegalStateException("weather service returned invalid JSON");
                    }
                }
                if (!isRetryable(response.statusCode()) || attempt == 1) {
                    throw new IllegalStateException(
                            "weather service request failed with HTTP " + response.statusCode());
                }
            } catch (IOException exception) {
                transportFailure = exception;
                if (attempt == 1) {
                    throw exception;
                }
            }
        }
        if (transportFailure != null) {
            throw transportFailure;
        }
        throw new IllegalStateException(
                "weather service request failed with HTTP "
                        + (response == null ? "unknown" : response.statusCode()));
    }

    private ObjectNode toResult(Location location, JsonNode forecast) {
        JsonNode current = forecast.path("current");
        JsonNode daily = forecast.path("daily");
        if (!current.isObject() || !daily.isObject()) {
            throw new IllegalStateException("weather forecast response is incomplete");
        }
        ObjectNode result = json.createObjectNode();
        ObjectNode locationNode = result.putObject("location");
        locationNode.put("name", location.name());
        locationNode.put("admin1", location.admin1());
        locationNode.put("country", location.country());
        locationNode.put("latitude", location.latitude());
        locationNode.put("longitude", location.longitude());
        result.put("timezone", forecast.path("timezone").asText("GMT"));

        ObjectNode currentNode = result.putObject("current");
        copy(current, currentNode, "time");
        copy(current, currentNode, "temperature_2m");
        copy(current, currentNode, "apparent_temperature");
        copy(current, currentNode, "relative_humidity_2m");
        copy(current, currentNode, "precipitation");
        copy(current, currentNode, "weather_code");
        copy(current, currentNode, "wind_speed_10m");

        ArrayNode days = result.putArray("daily");
        JsonNode dates = daily.path("time");
        if (!dates.isArray()) {
            throw new IllegalStateException("weather daily response is invalid");
        }
        for (int index = 0; index < dates.size(); index++) {
            ObjectNode day = days.addObject();
            copyIndex(daily, day, "time", index);
            copyIndex(daily, day, "weather_code", index);
            copyIndex(daily, day, "temperature_2m_max", index);
            copyIndex(daily, day, "temperature_2m_min", index);
            copyIndex(daily, day, "precipitation_probability_max", index);
        }
        return result;
    }

    private static void copy(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.set(field, value);
        }
    }

    private static void copyIndex(JsonNode source, ObjectNode target, String field, int index) {
        JsonNode values = source.get(field);
        if (values != null && values.isArray() && index < values.size()) {
            target.set(field, values.get(index));
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new IllegalStateException("weather request timed out");
        }
        Duration remaining = Duration.ofNanos(nanos);
        return remaining.compareTo(REQUEST_TIMEOUT) < 0 ? remaining : REQUEST_TIMEOUT;
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    record WeatherHttpResponse(int statusCode, String body) {
        WeatherHttpResponse {
            if (body == null) {
                body = "";
            }
        }
    }

    @FunctionalInterface
    interface WeatherHttpTransport {
        WeatherHttpResponse get(URI uri, Duration timeout)
                throws IOException, InterruptedException;
    }

    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static final class JdkWeatherHttpTransport implements WeatherHttpTransport {
        private final HttpClient client = newHttpClient();

        @Override
        public WeatherHttpResponse get(URI uri, Duration timeout)
                throws IOException, InterruptedException {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(timeout)
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return new WeatherHttpResponse(response.statusCode(), readBoundedBody(body));
            }
        }
    }

    static String readBoundedBody(InputStream body) throws IOException {
        if (body == null) {
            return "";
        }
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IOException("weather response exceeded safe size limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record Location(
            String name, String country, String admin1, double latitude, double longitude) {}
}
