package io.github.tinkerlgd2026.agentscope.cls.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ClsSpanRecord(
        String traceID,
        String spanID,
        String parentSpanID,
        String name,
        String kind,
        String start,
        String end,
        String duration,
        String statusCode,
        String statusMessage,
        String attribute,
        String resource,
        String traceState,
        String links,
        String logs) {

    public Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("traceID", traceID);
        fields.put("spanID", spanID);
        fields.put("parentSpanID", parentSpanID);
        fields.put("name", name);
        fields.put("kind", kind);
        fields.put("start", start);
        fields.put("end", end);
        fields.put("duration", duration);
        fields.put("statusCode", statusCode);
        fields.put("statusMessage", statusMessage == null ? "" : statusMessage);
        fields.put("attribute", attribute);
        fields.put("resource", resource);
        fields.put("traceState", traceState == null ? "" : traceState);
        fields.put("links", links == null ? "[]" : links);
        fields.put("logs", logs == null ? "[]" : logs);
        return Collections.unmodifiableMap(fields);
    }
}
