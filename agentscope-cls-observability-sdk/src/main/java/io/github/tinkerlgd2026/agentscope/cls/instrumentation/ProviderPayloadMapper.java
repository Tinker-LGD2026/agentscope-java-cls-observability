package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit AgentScope 2.0.3 provider-payload mapping without reflective getter discovery. */
final class ProviderPayloadMapper {

    Map<String, Object> map(Msg message) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "id", message.getId());
        put(result, "name", message.getName());
        result.put(
                "role",
                message.getRole() == null
                        ? "unknown"
                        : message.getRole().name().toLowerCase(Locale.ROOT));
        result.put("timestamp", message.getTimestamp());
        result.put("metadata", safeMap(message.getMetadata()));
        if (message.getGenerateReason() != null) {
            result.put("generate_reason", message.getGenerateReason().toString());
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (message.getContent() != null) {
            for (ContentBlock block : message.getContent()) {
                if (block != null) {
                    blocks.add(map(block));
                }
            }
        }
        result.put("content", List.copyOf(blocks));
        return Map.copyOf(result);
    }

    Map<String, Object> map(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return fields("text", "text", text.getText());
        }
        if (block instanceof ThinkingBlock thinking) {
            Map<String, Object> result = mutable("thinking");
            put(result, "thinking", thinking.getThinking());
            result.put("metadata", safeMap(thinking.getMetadata()));
            return Map.copyOf(result);
        }
        if (block instanceof ToolUseBlock tool) {
            Map<String, Object> result = mutable("tool_use");
            put(result, "id", tool.getId());
            put(result, "name", tool.getName());
            result.put("input", safeMap(tool.getInput()));
            put(result, "content", tool.getContent());
            result.put("metadata", safeMap(tool.getMetadata()));
            if (tool.getState() != null) {
                result.put("state", tool.getState().getValue());
            }
            return Map.copyOf(result);
        }
        if (block instanceof ToolResultBlock tool) {
            Map<String, Object> result = mutable("tool_result");
            put(result, "id", tool.getId());
            put(result, "name", tool.getName());
            List<Map<String, Object>> output = new ArrayList<>();
            if (tool.getOutput() != null) {
                for (ContentBlock item : tool.getOutput()) {
                    if (item != null) {
                        output.add(map(item));
                    }
                }
            }
            result.put("output", List.copyOf(output));
            result.put("metadata", safeMap(tool.getMetadata()));
            if (tool.getState() != null) {
                result.put("state", tool.getState().getValue());
            }
            return Map.copyOf(result);
        }
        if (block instanceof ImageBlock image) {
            Map<String, Object> result = mutable("image");
            result.put("source", mapSource(image.getSource()));
            put(result, "min_pixels", image.getMinPixels());
            put(result, "max_pixels", image.getMaxPixels());
            return Map.copyOf(result);
        }
        if (block instanceof AudioBlock audio) {
            return fields("audio", "source", mapSource(audio.getSource()));
        }
        if (block instanceof VideoBlock video) {
            Map<String, Object> result = mutable("video");
            result.put("source", mapSource(video.getSource()));
            put(result, "fps", video.getFps());
            put(result, "max_frames", video.getMaxFrames());
            put(result, "min_pixels", video.getMinPixels());
            put(result, "max_pixels", video.getMaxPixels());
            put(result, "total_pixels", video.getTotalPixels());
            return Map.copyOf(result);
        }
        if (block instanceof DataBlock data) {
            Map<String, Object> result = mutable("data");
            result.put("source", mapSource(data.getSource()));
            put(result, "id", data.getId());
            put(result, "name", data.getName());
            return Map.copyOf(result);
        }
        if (block instanceof HintBlock hint) {
            Map<String, Object> result = mutable("hint");
            put(result, "id", hint.getId());
            put(result, "hint", hint.getHint());
            put(result, "source", hint.getSource());
            return Map.copyOf(result);
        }
        return Map.of(
                "type", block.getClass().getSimpleName().toLowerCase(Locale.ROOT),
                "unsupported", true);
    }

    private static Map<String, Object> mapSource(Source source) {
        if (source instanceof URLSource url) {
            Map<String, Object> result = mutable("url");
            put(result, "url", url.getUrl());
            put(result, "mime_type", url.getMimeType());
            return Map.copyOf(result);
        }
        if (source instanceof Base64Source base64) {
            Map<String, Object> result = mutable("base64");
            put(result, "media_type", base64.getMediaType());
            put(result, "data", base64.getData());
            return Map.copyOf(result);
        }
        return source == null
                ? Map.of("type", "unknown", "unsupported", true)
                : Map.of(
                        "type", source.getClass().getSimpleName().toLowerCase(Locale.ROOT),
                        "unsupported", true);
    }

    private static Map<String, Object> mutable(String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        return result;
    }

    private static Map<String, Object> fields(String type, String key, Object value) {
        Map<String, Object> result = mutable(type);
        put(result, key, value);
        return Map.copyOf(result);
    }

    private static Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
