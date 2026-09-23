package io.github.tinkerlgd2026.agentscope.cls.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates captured gen_ai message arrays, including every known part shape. */
public final class MessageSchemaValidator {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_RESULT_NESTING_DEPTH = 16;
    private static final Set<String> HASH_PART_TYPES =
            Set.of("text_hash", "reasoning_hash", "content_hash");

    private final ProviderPayloadSchemaValidator providerPayloadValidator;

    public MessageSchemaValidator() {
        this(new ProviderPayloadSchemaValidator());
    }

    MessageSchemaValidator(ProviderPayloadSchemaValidator providerPayloadValidator) {
        if (providerPayloadValidator == null) {
            throw new IllegalArgumentException("providerPayloadValidator is required");
        }
        this.providerPayloadValidator = providerPayloadValidator;
    }

    public void validate(List<String> errors, JsonNode messages, String attributeKey) {
        String prefix = "attribute." + attributeKey;
        for (JsonNode message : messages) {
            if (!message.isObject()) {
                errors.add(prefix + " message must be an object");
                continue;
            }
            JsonNode role = message.get("role");
            if (role == null || !role.isTextual() || role.asText().isBlank()) {
                errors.add(prefix + " message.role is required");
            }
            JsonNode parts = message.get("parts");
            if (parts == null) {
                continue;
            }
            if (!parts.isArray()) {
                errors.add(prefix + " message.parts must be a JSON array");
                continue;
            }
            validateParts(errors, parts, prefix, 0);
        }
    }

    private void validateParts(List<String> errors, JsonNode parts, String prefix, int depth) {
        for (JsonNode part : parts) {
            if (!part.isObject()) {
                errors.add(prefix + " part must be an object");
                continue;
            }
            String type = part.path("type").asText();
            switch (type) {
                case "text" -> validateTextContent(errors, part.get("content"), prefix);
                case "reasoning" -> validateReasoningContent(errors, part.get("content"), prefix);
                case "tool_call" -> validateToolCall(errors, part, prefix);
                case "tool_call_response" ->
                        validateToolResponse(errors, part, prefix, depth);
                case "provider_payload" ->
                        providerPayloadValidator.validate(errors, part, prefix);
                default -> {
                    if (HASH_PART_TYPES.contains(type)) {
                        validateHashPart(errors, part, prefix, type);
                    }
                    // Unknown future part types are accepted for forward compatibility.
                }
            }
        }
    }

    private static void validateTextContent(
            List<String> errors, JsonNode content, String prefix) {
        if (content == null || (!content.isTextual() && !isSafeSummary(content))) {
            errors.add(prefix + " text.content must be a string or truncated summary");
        }
    }

    private static void validateReasoningContent(
            List<String> errors, JsonNode content, String prefix) {
        if (content == null || (!content.isTextual() && !isSafeSummary(content))) {
            errors.add(prefix + " reasoning.content summary is invalid");
        }
    }

    private static boolean isSafeSummary(JsonNode content) {
        if (!content.isObject()
                || content.size() != 3
                || !content.path("truncated").isBoolean()
                || !content.path("truncated").asBoolean()
                || !content.path("preview").isTextual()) {
            return false;
        }
        JsonNode originalBytes = content.get("original_bytes");
        return originalBytes != null
                && originalBytes.isIntegralNumber()
                && originalBytes.canConvertToLong()
                && originalBytes.longValue() >= 0;
    }

    private static void validateToolCall(List<String> errors, JsonNode part, String prefix) {
        requireOptionalText(errors, part, "id", prefix + " tool_call.id");
        requireOptionalText(errors, part, "name", prefix + " tool_call.name");
        requireOptionalText(errors, part, "state", prefix + " tool_call.state");
    }

    private void validateToolResponse(
            List<String> errors, JsonNode part, String prefix, int depth) {
        requireOptionalText(errors, part, "id", prefix + " tool_call_response.id");
        requireOptionalText(errors, part, "name", prefix + " tool_call_response.name");
        JsonNode result = part.get("result");
        if (result == null) {
            return;
        }
        if (!result.isArray()) {
            errors.add(prefix + " tool_call_response.result must be a JSON array");
            return;
        }
        if (depth + 1 >= MAX_RESULT_NESTING_DEPTH) {
            errors.add(
                    prefix + " tool_call_response.result exceeds maximum nesting depth");
            return;
        }
        validateParts(errors, result, prefix, depth + 1);
    }

    private static void requireOptionalText(
            List<String> errors, JsonNode part, String key, String field) {
        JsonNode value = part.get(key);
        if (value != null && !value.isTextual()) {
            errors.add(field + " must be a string");
        }
    }

    private static void validateHashPart(
            List<String> errors, JsonNode part, String prefix, String type) {
        JsonNode sha256 = part.get("sha256");
        if (sha256 == null
                || !sha256.isTextual()
                || !SHA_256.matcher(sha256.asText()).matches()) {
            errors.add(prefix + " " + type + ".sha256 is invalid");
        }
        JsonNode originalBytes = part.get("original_bytes");
        if (originalBytes == null) {
            errors.add(prefix + " " + type + ".original_bytes must be an integer");
        } else if (!originalBytes.isIntegralNumber()) {
            errors.add(prefix + " " + type + ".original_bytes must be an integer");
        } else if (!originalBytes.canConvertToLong()) {
            errors.add(
                    prefix + " " + type + ".original_bytes must fit a signed 64-bit integer");
        } else if (originalBytes.longValue() < 0) {
            errors.add(prefix + " " + type + ".original_bytes must be non-negative");
        }
    }
}
