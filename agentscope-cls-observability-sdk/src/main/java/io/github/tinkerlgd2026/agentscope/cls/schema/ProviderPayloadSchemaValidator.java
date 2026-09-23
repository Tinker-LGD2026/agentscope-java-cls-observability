package io.github.tinkerlgd2026.agentscope.cls.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the bounded provider payload capture envelope shape. */
public final class ProviderPayloadSchemaValidator {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> MODES = Set.of("off", "hash", "truncate", "full");

    public void validate(List<String> errors, JsonNode part, String prefix) {
        JsonNode envelope = part.get("provider_payload");
        if (envelope == null || !envelope.isObject()) {
            errors.add(prefix + " provider_payload envelope must be an object");
            return;
        }
        JsonNode mode = envelope.get("mode");
        String modeValue =
                mode != null && mode.isTextual() ? mode.asText() : null;
        if (modeValue == null || !MODES.contains(modeValue)) {
            errors.add(prefix + " provider_payload.mode is unsupported");
            return;
        }
        JsonNode complete = envelope.get("complete");
        if (complete == null || !complete.isBoolean()) {
            errors.add(prefix + " provider_payload.complete must be a boolean");
        }
        JsonNode captureError = envelope.get("capture_error");
        if (captureError != null && !captureError.isBoolean()) {
            errors.add(prefix + " provider_payload.capture_error must be a boolean");
        }
        switch (modeValue) {
            case "off" -> {
                if (envelope.has("payload")) {
                    errors.add(
                            prefix
                                    + " provider_payload off mode must not contain a plaintext payload");
                }
            }
            case "hash" -> {
                if (envelope.has("payload")) {
                    errors.add(
                            prefix
                                    + " provider_payload hash mode must not contain a plaintext payload");
                }
                JsonNode sha256 = envelope.get("sha256");
                if (sha256 == null
                        || !sha256.isTextual()
                        || !SHA_256.matcher(sha256.asText()).matches()) {
                    errors.add(prefix + " provider_payload.sha256 is invalid");
                }
                requireByteCount(errors, envelope, prefix, "original_bytes");
            }
            case "truncate" -> {
                JsonNode payload = envelope.get("payload");
                if (payload == null || !payload.isTextual()) {
                    errors.add(prefix + " provider_payload.payload must be a string");
                }
                requireByteCount(errors, envelope, prefix, "retained_bytes");
                requireByteCount(errors, envelope, prefix, "original_bytes");
            }
            case "full" -> {
                if (!envelope.has("payload")) {
                    errors.add(prefix + " provider_payload.payload is required");
                }
            }
            default -> {
                // Unreachable: mode membership is checked above.
            }
        }
    }

    private static void requireByteCount(
            List<String> errors, JsonNode envelope, String prefix, String key) {
        String effectiveKey = key;
        JsonNode value = envelope.get(key);
        if (value == null) {
            if ("original_bytes".equals(key) && envelope.has("original_bytes_at_least")) {
                effectiveKey = "original_bytes_at_least";
                value = envelope.get(effectiveKey);
            } else {
                errors.add(prefix + " provider_payload." + key + " must be an integer");
                return;
            }
        }
        if (!value.isIntegralNumber()) {
            errors.add(prefix + " provider_payload." + effectiveKey + " must be an integer");
        } else if (!value.canConvertToLong()) {
            errors.add(
                    prefix
                            + " provider_payload."
                            + effectiveKey
                            + " must fit a signed 64-bit integer");
        } else if (value.longValue() < 0) {
            errors.add(prefix + " provider_payload." + effectiveKey + " must be non-negative");
        }
    }
}
