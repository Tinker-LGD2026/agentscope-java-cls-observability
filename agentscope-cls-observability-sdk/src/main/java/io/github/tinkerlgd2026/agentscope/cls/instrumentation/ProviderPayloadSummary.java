package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.privacy.CanonicalPayloadCapture;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the bounded provider_payload part summaries appended to captured messages. Keeps the
 * last 32 messages and wraps every payload in the canonical capture envelope.
 */
final class ProviderPayloadSummary {
    private static final int MAX_MESSAGES = 32;
    private static final long MAX_ENVELOPE_BYTES = 1_572_864L;

    private final AgentScopeMessageConverter messageConverter;
    private final CanonicalPayloadCapture canonicalPayloadCapture;
    private final ContentCaptureMode providerPayloadCaptureMode;
    private final int truncatePreviewBytes;
    private final int maxContentBytes;

    ProviderPayloadSummary(
            AgentScopeMessageConverter messageConverter,
            CanonicalPayloadCapture canonicalPayloadCapture,
            ContentCaptureMode providerPayloadCaptureMode,
            int truncatePreviewBytes,
            int maxContentBytes) {
        this.messageConverter = Objects.requireNonNull(messageConverter, "messageConverter");
        this.canonicalPayloadCapture =
                Objects.requireNonNull(canonicalPayloadCapture, "canonicalPayloadCapture");
        this.providerPayloadCaptureMode =
                Objects.requireNonNull(
                        providerPayloadCaptureMode, "providerPayloadCaptureMode");
        this.truncatePreviewBytes = truncatePreviewBytes;
        this.maxContentBytes = maxContentBytes;
    }

    List<Map<String, Object>> summarize(
            List<io.agentscope.core.message.Msg> messages) {
        if (providerPayloadCaptureMode == ContentCaptureMode.OFF || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int first = Math.max(0, messages.size() - MAX_MESSAGES);
        for (int index = first; index < messages.size(); index++) {
            io.agentscope.core.message.Msg message = messages.get(index);
            if (message == null) {
                continue;
            }
            List<Map<String, Object>> parts = new ArrayList<>();
            addProviderPart(parts, messageConverter.providerPayload(message));
            if (message.getContent() != null) {
                for (io.agentscope.core.message.ContentBlock block : message.getContent()) {
                    if (block != null) {
                        Map<String, Object> payload = messageConverter.providerPayload(block);
                        if (payload.size() > 1 || !payload.containsKey("type")) {
                            addProviderPart(parts, payload);
                        }
                    }
                }
            }
            if (!parts.isEmpty()) {
                String role =
                        message.getRole() == null
                                ? "unknown"
                                : message.getRole().name().toLowerCase(Locale.ROOT);
                result.add(Map.of("role", role, "parts", List.copyOf(parts)));
            }
        }
        return List.copyOf(result);
    }

    private void addProviderPart(
            List<Map<String, Object>> parts, Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return;
        }
        Map<String, Object> envelope =
                canonicalPayloadCapture.capture(
                        payload,
                        providerPayloadCaptureMode,
                        truncatePreviewBytes,
                        CanonicalPayloadCapture.CaptureBudget.fixed(
                                Math.min(MAX_ENVELOPE_BYTES, maxContentBytes)));
        parts.add(
                Map.of(
                        "type", "provider_payload",
                        "provider_payload", envelope));
    }
}
