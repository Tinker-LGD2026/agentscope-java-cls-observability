package io.github.tinkerlgd2026.agentscope.cls.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ClsSpanValidator {
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Map<String, String> OPERATIONS =
            Map.of(
                    "entry", "enter_application",
                    "agent", "invoke_agent",
                    "step", "react",
                    "chat", "chat",
                    "tool", "execute_tool");
    private static final Set<String> SPAN_KINDS =
            Set.of("internal", "client", "server", "producer", "consumer");
    private static final Set<String> STATUS_CODES = Set.of("OK", "ERROR", "UNSET");
    private static final List<String> MESSAGE_ATTRIBUTES =
            List.of(
                    "gen_ai.input.messages",
                    "gen_ai.input.messages_delta",
                    "gen_ai.output.messages");
    private static final List<String> INTEGER_ATTRIBUTES =
            List.of(
                    "gen_ai.chat.duration_ms",
                    "gen_ai.tool.call.duration_ms",
                    "gen_ai.usage.input_tokens",
                    "gen_ai.usage.output_tokens",
                    "gen_ai.usage.total_tokens",
                    "gen_ai.usage.cache_read.input_tokens",
                    "gen_ai.usage.cache_creation.input_tokens",
                    "gen_ai.usage.cache_miss.input_tokens",
                    "gen_ai.usage.reasoning_output_tokens",
                    "gen_ai.agent.message_count",
                    "gen_ai.agent.tool_call_count",
                    "gen_ai.react.round",
                    ClsFields.REASONING_BLOCK_COUNT,
                    ClsFields.REASONING_OUTPUT_BYTES,
                    ClsFields.REASONING_DURATION_MS,
                    ClsFields.REASONING_TTFT_MS,
                    ClsFields.REASONING_MALFORMED_EVENTS,
                    ClsFields.RESPONSE_TTFT_MS);
    private static final List<String> BOOLEAN_ATTRIBUTES =
            List.of(
                    ClsFields.REASONING_PRESENT,
                    ClsFields.REASONING_TRUNCATED,
                    ClsFields.TURN_COMPLETED,
                    ClsFields.INCOMPLETE,
                    ClsFields.PARTIAL_FAILURE,
                    ClsFields.CAPTURE_TRUNCATED,
                    ClsFields.CAPTURE_HASH_COMPLETE);
    private static final List<String> NON_NEGATIVE_LONG_ATTRIBUTES =
            List.of(
                    ClsFields.HITL_WAIT_COUNT,
                    ClsFields.HITL_TOTAL_WAIT_MS,
                    ClsFields.FAILED_TOOL_COUNT,
                    ClsFields.CAPTURE_ORIGINAL_BYTES,
                    ClsFields.CAPTURE_RETAINED_BYTES,
                    ClsFields.CAPTURE_CAPACITY_DROPPED_PARTS,
                    ClsFields.CAPTURE_CAPACITY_DROPPED_BYTES,
                    ClsFields.CAPTURE_FAILURE_COUNT);
    private static final Set<String> TURN_FINISH_REASONS =
            Set.of(
                    "normal",
                    "incomplete_completion",
                    "await_timeout",
                    "control_capacity",
                    "denied",
                    "max_iters",
                    "interrupted",
                    "cancelled",
                    "error",
                    "shutdown");
    private static final Set<String> CAPTURE_MODES =
            Set.of("off", "hash", "truncate", "full");
    private static final List<String> COMMON_ATTRIBUTES =
            List.of(
                    ClsFields.SPAN_KIND,
                    ClsFields.OPERATION_NAME,
                    ClsFields.AGENT_TYPE,
                    ClsFields.SESSION_ID,
                    ClsFields.TURN_ID,
                    ClsFields.USER_ID,
                    ClsFields.USER_NAME);

    private final ObjectMapper objectMapper;
    private final MessageSchemaValidator messageValidator;

    public ClsSpanValidator(ObjectMapper objectMapper) {
        this(objectMapper, new MessageSchemaValidator());
    }

    ClsSpanValidator(ObjectMapper objectMapper, MessageSchemaValidator messageValidator) {
        if (objectMapper == null || messageValidator == null) {
            throw new IllegalArgumentException("objectMapper and messageValidator are required");
        }
        this.objectMapper = objectMapper;
        this.messageValidator = messageValidator;
    }

    /**
     * Validates a fully materialized record. Note: a well-formed truncated field envelope is
     * trusted as produced by {@link BoundedFieldEncoder}; callers must not pass untrusted
     * pre-cropping content through this overload to bypass content validation.
     */
    public List<String> validate(ClsSpanRecord record) {
        if (record == null) {
            return List.of("record is required");
        }
        List<String> errors = new ArrayList<>();
        JsonNode attributes = parseObject(errors, "attribute", record.attribute());
        JsonNode resources = parseObject(errors, "resource", record.resource());
        parseArrayOrEnvelope(errors, "links", record.links());
        parseArrayOrEnvelope(errors, "logs", record.logs());
        validateRecord(errors, record, attributes, resources);
        return List.copyOf(errors);
    }

    public List<String> validate(ClsSpanDocument document) {
        if (document == null) {
            return List.of("document is required");
        }
        List<String> errors = new ArrayList<>();
        validateRecord(errors, document.record(), document.attribute(), document.resource());
        return List.copyOf(errors);
    }

    private void validateRecord(
            List<String> errors,
            ClsSpanRecord record,
            JsonNode attributes,
            JsonNode resources) {
        requirePattern(errors, "traceID", record.traceID(), TRACE_ID);
        requirePattern(errors, "spanID", record.spanID(), SPAN_ID);
        if (record.parentSpanID() == null
                || (!record.parentSpanID().isEmpty()
                        && !SPAN_ID.matcher(record.parentSpanID()).matches())) {
            errors.add("parentSpanID must be empty or a 16 character lowercase hex value");
        }
        requireText(errors, "name", record.name());
        requireText(errors, "kind", record.kind());
        requireText(errors, "statusCode", record.statusCode());
        if (record.kind() != null && !SPAN_KINDS.contains(record.kind())) {
            errors.add("kind is unsupported");
        }
        if (record.statusCode() != null && !STATUS_CODES.contains(record.statusCode())) {
            errors.add("statusCode is unsupported");
        }
        validateTimes(errors, record);

        if (resources != null && !truncatedEnvelope(errors, "resource", resources)) {
            requireJsonText(errors, resources, "resource", "service.name");
            requireJsonText(errors, resources, "resource", "host.name");
        }
        if (attributes != null && !truncatedEnvelope(errors, "attribute", attributes)) {
            for (String key : COMMON_ATTRIBUTES) {
                requireJsonText(errors, attributes, "attribute", key);
            }
            String spanKind = text(attributes, ClsFields.SPAN_KIND);
            String operation = text(attributes, ClsFields.OPERATION_NAME);
            if (spanKind != null && !OPERATIONS.containsKey(spanKind)) {
                errors.add("attribute.gen_ai.span.kind is unsupported");
            } else if (spanKind != null
                    && operation != null
                    && !OPERATIONS.get(spanKind).equals(operation)) {
                errors.add("attribute.gen_ai.operation.name does not match span kind");
            }
            validateAttributeTypes(errors, attributes);
            validateSpanSpecificFields(errors, attributes, spanKind);
        }
    }

    private void validateAttributeTypes(List<String> errors, JsonNode attributes) {
        for (String key : MESSAGE_ATTRIBUTES) {
            JsonNode value = attributes.get(key);
            if (value != null && !value.isArray()) {
                errors.add("attribute." + key + " must be a JSON array");
            } else if (value != null) {
                messageValidator.validate(errors, value, key);
            }
        }
        for (String key : INTEGER_ATTRIBUTES) {
            JsonNode value = attributes.get(key);
            if (value != null) {
                validateNonNegativeLong(errors, value, "attribute." + key);
            }
        }
        for (String key : NON_NEGATIVE_LONG_ATTRIBUTES) {
            JsonNode value = attributes.get(key);
            if (value != null) {
                validateNonNegativeLong(errors, value, "attribute." + key);
            }
        }
        for (String key : BOOLEAN_ATTRIBUTES) {
            JsonNode value = attributes.get(key);
            if (value != null && !value.isBoolean()) {
                errors.add("attribute." + key + " must be a boolean");
            }
        }
        JsonNode captureMode = attributes.get(ClsFields.REASONING_CAPTURE_MODE);
        if (captureMode != null
                && (!captureMode.isTextual() || !CAPTURE_MODES.contains(captureMode.asText()))) {
            errors.add("attribute." + ClsFields.REASONING_CAPTURE_MODE + " is unsupported");
        }
        JsonNode finishReason = attributes.get(ClsFields.TURN_FINISH_REASON);
        if (finishReason != null
                && (!finishReason.isTextual()
                        || !TURN_FINISH_REASONS.contains(finishReason.asText()))) {
            errors.add("attribute." + ClsFields.TURN_FINISH_REASON + " is unsupported");
        }
        JsonNode resumeFrom = attributes.get(ClsFields.TURN_RESUME_FROM_TURN_ID);
        if (resumeFrom != null && !resumeFrom.isTextual()) {
            errors.add(
                    "attribute." + ClsFields.TURN_RESUME_FROM_TURN_ID + " must be a string");
        }
        JsonNode finishReasons = attributes.get("gen_ai.response.finish_reasons");
        if (finishReasons != null
                && (!finishReasons.isArray()
                        || !allTextValues(finishReasons))) {
            errors.add("attribute.gen_ai.response.finish_reasons must be a string array");
        }
        validateObjectOrText(errors, attributes, "gen_ai.tool.call.arguments");
        validateObjectOrText(errors, attributes, "gen_ai.tool.call.result");
    }

    private static void validateSpanSpecificFields(
            List<String> errors, JsonNode attributes, String spanKind) {
        if ("tool".equals(spanKind)) {
            requireJsonText(errors, attributes, "attribute", "gen_ai.tool.call.id");
            requireJsonText(errors, attributes, "attribute", "gen_ai.tool.name");
            requireJsonText(errors, attributes, "attribute", "gen_ai.tool.type");
            requireJsonInteger(
                    errors, attributes, "attribute", "gen_ai.tool.call.duration_ms");
        }
    }

    private static void validateNonNegativeLong(
            List<String> errors, JsonNode value, String field) {
        if (!value.isIntegralNumber()) {
            errors.add(field + " must be an integer");
        } else if (!value.canConvertToLong()) {
            errors.add(field + " must fit a signed 64-bit integer");
        } else if (value.longValue() < 0) {
            errors.add(field + " must be non-negative");
        }
    }

    private static boolean allTextValues(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static void validateObjectOrText(
            List<String> errors, JsonNode attributes, String key) {
        JsonNode value = attributes.get(key);
        if (value != null && !value.isObject() && !value.isTextual()) {
            errors.add("attribute." + key + " must be a JSON object or string");
        }
    }

    private void validateTimes(List<String> errors, ClsSpanRecord record) {
        try {
            long start = Long.parseLong(record.start());
            long end = Long.parseLong(record.end());
            long duration = Long.parseLong(record.duration());
            if (start < 0 || end < start || duration != end - start) {
                errors.add("start, end and duration are inconsistent");
            }
        } catch (RuntimeException exception) {
            errors.add("start, end and duration must be non-negative integer strings");
        }
    }

    // Returns true when the node is a valid truncated field envelope, meaning the original
    // content was cropped by BoundedFieldEncoder and content checks must be skipped.
    private static boolean truncatedEnvelope(
            List<String> errors, String field, JsonNode node) {
        JsonNode truncated = node.get("truncated");
        if (truncated == null || !truncated.isBoolean() || !truncated.asBoolean()) {
            return false;
        }
        JsonNode sha256 = node.get("sha256");
        JsonNode preview = node.get("preview");
        JsonNode originalBytes = node.get("original_bytes");
        boolean valid =
                node.size() == 4
                        && sha256 != null
                        && sha256.isTextual()
                        && SHA_256.matcher(sha256.asText()).matches()
                        && preview != null
                        && preview.isTextual()
                        && originalBytes != null
                        && originalBytes.isIntegralNumber()
                        && originalBytes.canConvertToLong()
                        && originalBytes.longValue() >= 0;
        if (!valid) {
            errors.add(field + " is not a valid truncated field envelope");
        }
        return valid;
    }

    private JsonNode parseArrayOrEnvelope(List<String> errors, String field, String json) {
        JsonNode value = parseJson(errors, field, json);
        if (value == null || value.isArray()) {
            return value;
        }
        if (value.isObject()
                && value.path("truncated").isBoolean()
                && value.path("truncated").asBoolean()) {
            return truncatedEnvelope(errors, field, value) ? value : null;
        }
        errors.add(field + " must encode a JSON array");
        return null;
    }

    private JsonNode parseObject(List<String> errors, String field, String json) {
        JsonNode value = parseJson(errors, field, json);
        if (value != null && !value.isObject()) {
            errors.add(field + " must encode a JSON object");
            return null;
        }
        return value;
    }

    private JsonNode parseJson(List<String> errors, String field, String json) {
        if (json == null || json.isBlank()) {
            errors.add(field + " is required");
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            errors.add(field + " must contain valid JSON");
            return null;
        }
    }

    private static void requirePattern(
            List<String> errors, String field, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            errors.add(field + " has an invalid format");
        }
    }

    private static void requireText(List<String> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(field + " is required");
        }
    }

    private static void requireJsonText(
            List<String> errors, JsonNode object, String prefix, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            errors.add(prefix + "." + key + " is required");
        }
    }

    private static void requireJsonInteger(
            List<String> errors, JsonNode object, String prefix, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isIntegralNumber()) {
            errors.add(prefix + "." + key + " must be an integer");
        }
    }

    private static String text(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
