package io.github.tinkerlgd2026.agentscope.cls.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts an OpenTelemetry span into the CLS Agent Trace log layout. */
public final class ClsSpanEncoder {
    private static final Set<String> STRUCTURED_JSON_ATTRIBUTES =
            Set.of(
                    "gen_ai.input.messages",
                    "gen_ai.input.messages_delta",
                    "gen_ai.output.messages",
                    "gen_ai.tool.call.arguments",
                    "gen_ai.tool.call.result");

    private final ObjectMapper objectMapper;

    public ClsSpanEncoder(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        this.objectMapper = objectMapper;
    }

    public ClsSpanRecord encode(SpanData span) {
        return encodeDocument(span).record();
    }

    public ClsSpanDocument encodeDocument(SpanData span) {
        if (span == null) {
            throw new IllegalArgumentException("span is required");
        }
        ObjectNode attribute = attributes(span.getAttributes(), true);
        ObjectNode resource = attributes(span.getResource().getAttributes(), false);
        ArrayNode links = links(span.getLinks());
        ArrayNode logs = events(span.getEvents());
        ClsSpanRecord record =
                new ClsSpanRecord(
                        span.getTraceId(),
                        span.getSpanId(),
                        span.getParentSpanContext().isValid() ? span.getParentSpanId() : "",
                        span.getName(),
                        span.getKind().name().toLowerCase(Locale.ROOT),
                        Long.toString(span.getStartEpochNanos()),
                        Long.toString(span.getEndEpochNanos()),
                        Long.toString(span.getEndEpochNanos() - span.getStartEpochNanos()),
                        span.getStatus().getStatusCode().name(),
                        nullToEmpty(span.getStatus().getDescription()),
                        json(attribute),
                        json(resource),
                        traceState(span),
                        json(links),
                        json(logs));
        return new ClsSpanDocument(record, attribute, resource, links, logs);
    }

    private ObjectNode attributes(Attributes attributes, boolean parseStructuredJson) {
        ObjectNode result = objectMapper.createObjectNode();
        for (Map.Entry<AttributeKey<?>, Object> entry : attributes.asMap().entrySet()) {
            String key = entry.getKey().getKey();
            result.set(key, value(key, entry.getValue(), parseStructuredJson));
        }
        return result;
    }

    private JsonNode value(String key, Object value, boolean parseStructuredJson) {
        if (parseStructuredJson
                && value instanceof String text
                && STRUCTURED_JSON_ATTRIBUTES.contains(key)) {
            try {
                return objectMapper.readTree(text);
            } catch (JsonProcessingException exception) {
                return objectMapper.getNodeFactory().textNode(text);
            }
        }
        if (value instanceof String text) {
            return objectMapper.getNodeFactory().textNode(text);
        }
        if (value instanceof Long number) {
            return objectMapper.getNodeFactory().numberNode(number);
        }
        if (value instanceof Double number) {
            return objectMapper.getNodeFactory().numberNode(number);
        }
        if (value instanceof Boolean flag) {
            return objectMapper.getNodeFactory().booleanNode(flag);
        }
        return objectMapper.valueToTree(value);
    }

    private ArrayNode links(List<LinkData> links) {
        ArrayNode result = objectMapper.createArrayNode();
        for (LinkData link : links) {
            ObjectNode item = result.addObject();
            item.put("traceID", link.getSpanContext().getTraceId());
            item.put("spanID", link.getSpanContext().getSpanId());
            item.put("traceState", traceState(link));
            item.set("attribute", attributes(link.getAttributes(), false));
        }
        return result;
    }

    private ArrayNode events(List<EventData> events) {
        ArrayNode result = objectMapper.createArrayNode();
        for (EventData event : events) {
            ObjectNode item = result.addObject();
            item.put("name", event.getName());
            item.put("timeUnixNano", Long.toString(event.getEpochNanos()));
            item.set("attribute", attributes(event.getAttributes(), false));
        }
        return result;
    }

    private static String traceState(SpanData span) {
        return traceState(span.getSpanContext().getTraceState().asMap());
    }

    private static String traceState(LinkData link) {
        return traceState(link.getSpanContext().getTraceState().asMap());
    }

    private static String traceState(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!result.isEmpty()) {
                result.append(',');
            }
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("span field cannot be encoded as JSON", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
