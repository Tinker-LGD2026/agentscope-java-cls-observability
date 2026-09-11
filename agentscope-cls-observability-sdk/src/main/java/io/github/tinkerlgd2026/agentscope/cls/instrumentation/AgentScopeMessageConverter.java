package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps AgentScope messages to the CLS {@code role} plus {@code parts} message layout.
 *
 * <p>Field order is preserved and null content is normalised so the produced payload is stable
 * across processes, which keeps {@code gen_ai.input.messages.hash} comparable.
 */
final class AgentScopeMessageConverter {
    private static final int MAX_MESSAGES = 32;
    private static final int MAX_SCANNED_PARTS = 256;
    private static final int MAX_REASONING_PARTS = 8;
    private static final int MAX_CONTENT_PARTS = 16;
    private static final int MAX_TOOL_PARTS = 8;
    private static final int MAX_NESTING_DEPTH = 16;

    List<Map<String, Object>> convert(@Nullable List<Msg> messages) {
        return convertBounded(messages).messages();
    }

    ConversionResult convertBounded(@Nullable List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ConversionResult(List.of(), true);
        }
        SelectionLimits limits = new SelectionLimits();
        List<Map<String, Object>> selected = new ArrayList<>();
        int firstMessage = Math.max(0, messages.size() - MAX_MESSAGES);
        if (firstMessage > 0) {
            limits.incomplete = true;
        }
        for (int index = messages.size() - 1; index >= firstMessage; index--) {
            Msg message = messages.get(index);
            if (message == null) {
                continue;
            }
            List<Map<String, Object>> parts = convertParts(message.getContent(), limits, 0);
            if (!parts.isEmpty()) {
                selected.add(0, convertMessage(message, parts));
            }
            if (limits.scanExhausted()) {
                limits.incomplete = true;
                break;
            }
        }
        return new ConversionResult(List.copyOf(selected), !limits.incomplete);
    }

    Map<String, Object> convert(Msg message) {
        return convertBounded(List.of(message)).messages().stream()
                .findFirst()
                .orElseGet(() -> convertMessage(message, List.of()));
    }

    private static Map<String, Object> convertMessage(
            Msg message, List<Map<String, Object>> parts) {
        Map<String, Object> result = new LinkedHashMap<>();
        String role =
                message.getRole() == null
                        ? "user"
                        : message.getRole().name().toLowerCase(Locale.ROOT);
        result.put("role", role);
        result.put("parts", parts);
        if (message.getName() != null && !message.getName().isBlank()) {
            result.put("name", message.getName());
        }
        return Collections.unmodifiableMap(result);
    }

    private List<Map<String, Object>> convertParts(
            @Nullable List<ContentBlock> blocks, SelectionLimits limits, int depth) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        if (depth >= MAX_NESTING_DEPTH) {
            limits.incomplete = true;
            return List.of();
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int index = blocks.size() - 1; index >= 0; index--) {
            if (!limits.tryScan()) {
                break;
            }
            ContentBlock block = blocks.get(index);
            PartKind kind = PartKind.of(block);
            if (block == null || !limits.tryAcquire(kind)) {
                limits.incomplete = true;
                continue;
            }
            Map<String, Object> part = convertPart(block, limits, depth);
            if (part != null) {
                parts.add(0, part);
            }
        }
        return List.copyOf(parts);
    }

    private @Nullable Map<String, Object> convertPart(
            ContentBlock block, SelectionLimits limits, int depth) {
        if (block instanceof TextBlock text) {
            return part("text", "content", defaultText(text.getText()));
        }
        if (block instanceof ThinkingBlock thinking) {
            return part("reasoning", "content", defaultText(thinking.getThinking()));
        }
        if (block instanceof ToolUseBlock tool) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_call");
            result.put("id", defaultText(tool.getId()));
            result.put("name", defaultText(tool.getName()));
            result.put("arguments", tool.getInput() == null ? Map.of() : tool.getInput());
            return Collections.unmodifiableMap(result);
        }
        if (block instanceof ToolResultBlock toolResult) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_call_response");
            result.put("id", defaultText(toolResult.getId()));
            result.put("result", convertParts(toolResult.getOutput(), limits, depth + 1));
            return Collections.unmodifiableMap(result);
        }
        return part(
                block.getClass().getSimpleName().toLowerCase(Locale.ROOT),
                "unsupported",
                true);
    }

    private static Map<String, Object> part(String type, String key, Object value) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", type);
        part.put(key, value);
        return Collections.unmodifiableMap(part);
    }

    private static String defaultText(@Nullable String value) {
        return value == null ? "" : value;
    }

    record ConversionResult(List<Map<String, Object>> messages, boolean complete) {
        ConversionResult {
            messages = List.copyOf(messages);
        }
    }

    private enum PartKind {
        REASONING,
        CONTENT,
        TOOL;

        private static PartKind of(@Nullable ContentBlock block) {
            if (block instanceof ThinkingBlock) {
                return REASONING;
            }
            if (block instanceof ToolUseBlock || block instanceof ToolResultBlock) {
                return TOOL;
            }
            return CONTENT;
        }
    }

    private static final class SelectionLimits {
        private int scannedParts;
        private int reasoningParts;
        private int contentParts;
        private int toolParts;
        private boolean incomplete;

        private boolean tryScan() {
            if (scannedParts >= MAX_SCANNED_PARTS) {
                incomplete = true;
                return false;
            }
            scannedParts++;
            return true;
        }

        private boolean scanExhausted() {
            return scannedParts >= MAX_SCANNED_PARTS;
        }

        private boolean tryAcquire(PartKind kind) {
            return switch (kind) {
                case REASONING -> reasoningParts++ < MAX_REASONING_PARTS;
                case CONTENT -> contentParts++ < MAX_CONTENT_PARTS;
                case TOOL -> toolParts++ < MAX_TOOL_PARTS;
            };
        }
    }
}
