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

    List<Map<String, Object>> convert(@Nullable List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        for (Msg message : messages) {
            if (message != null) {
                result.add(convert(message));
            }
        }
        return List.copyOf(result);
    }

    Map<String, Object> convert(Msg message) {
        Map<String, Object> result = new LinkedHashMap<>();
        String role =
                message.getRole() == null
                        ? "user"
                        : message.getRole().name().toLowerCase(Locale.ROOT);
        result.put("role", role);
        result.put("parts", convertParts(message.getContent()));
        if (message.getName() != null && !message.getName().isBlank()) {
            result.put("name", message.getName());
        }
        return Collections.unmodifiableMap(result);
    }

    private List<Map<String, Object>> convertParts(@Nullable List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> parts = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            Map<String, Object> part = convertPart(block);
            if (part != null) {
                parts.add(part);
            }
        }
        return List.copyOf(parts);
    }

    private @Nullable Map<String, Object> convertPart(@Nullable ContentBlock block) {
        if (block == null) {
            return null;
        }
        if (block instanceof TextBlock text) {
            return part("text", "content", defaultText(text.getText()));
        }
        if (block instanceof ThinkingBlock thinking) {
            return part("reasoning", "content", defaultText(thinking.getThinking()));
        }
        if (block instanceof ToolUseBlock tool) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "tool_call");
            part.put("id", defaultText(tool.getId()));
            part.put("name", defaultText(tool.getName()));
            part.put("arguments", tool.getInput() == null ? Map.of() : tool.getInput());
            return Collections.unmodifiableMap(part);
        }
        if (block instanceof ToolResultBlock toolResult) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "tool_call_response");
            part.put("id", defaultText(toolResult.getId()));
            part.put("result", convertParts(toolResult.getOutput()));
            return Collections.unmodifiableMap(part);
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
}
