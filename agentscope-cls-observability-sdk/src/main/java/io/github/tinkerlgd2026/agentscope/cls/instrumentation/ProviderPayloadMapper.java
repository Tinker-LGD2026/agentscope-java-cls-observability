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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit bounded AgentScope 2.0.3 provider-payload mapping without reflective getter discovery. */
final class ProviderPayloadMapper {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 1_024;
    private static final int MAX_COLLECTION_ITEMS = 256;

    Map<String, Object> map(Msg message) {
        return mapBounded(message).payload();
    }

    MappingResult mapBounded(Msg message) {
        MappingState state = new MappingState();
        Map<String, Object> result = mutable();
        put(result, "msg_metadata", state.boundedMap(message.getMetadata(), 1));
        put(result, "timestamp", message.getTimestamp());
        if (message.getGenerateReason() != null) {
            result.put("generate_reason", message.getGenerateReason().toString());
        }
        return new MappingResult(immutable(result), state.complete);
    }

    Map<String, Object> map(ContentBlock block) {
        return mapBounded(block).payload();
    }

    MappingResult mapBounded(ContentBlock block) {
        MappingState state = new MappingState();
        return new MappingResult(mapBlock(block, state, 0), state.complete);
    }

    private Map<String, Object> mapBlock(ContentBlock block, MappingState state, int depth) {
        if (!state.enter(depth)) {
            return Map.of("type", "unsupported", "unsupported", true);
        }
        if (block instanceof TextBlock) {
            return Map.of("type", "text");
        }
        if (block instanceof ThinkingBlock thinking) {
            Map<String, Object> result = typed("thinking");
            put(result, "metadata", state.boundedMap(thinking.getMetadata(), depth + 1));
            return immutable(result);
        }
        if (block instanceof ToolUseBlock tool) {
            Map<String, Object> result = typed("tool_use");
            put(result, "content", tool.getContent());
            put(result, "metadata", state.boundedMap(tool.getMetadata(), depth + 1));
            if (tool.getState() != null) {
                result.put("state", tool.getState().getValue());
            }
            return immutable(result);
        }
        if (block instanceof ToolResultBlock tool) {
            Map<String, Object> result = typed("tool_result");
            put(result, "metadata", state.boundedMap(tool.getMetadata(), depth + 1));
            if (tool.getState() != null) {
                result.put("state", tool.getState().getValue());
            }
            return immutable(result);
        }
        if (block instanceof ImageBlock image) {
            Map<String, Object> result = typed("image");
            result.put("source", mapSource(image.getSource(), state, depth + 1));
            put(result, "min_pixels", image.getMinPixels());
            put(result, "max_pixels", image.getMaxPixels());
            return immutable(result);
        }
        if (block instanceof AudioBlock audio) {
            Map<String, Object> result = typed("audio");
            result.put("source", mapSource(audio.getSource(), state, depth + 1));
            return immutable(result);
        }
        if (block instanceof VideoBlock video) {
            Map<String, Object> result = typed("video");
            result.put("source", mapSource(video.getSource(), state, depth + 1));
            put(result, "fps", video.getFps());
            put(result, "max_frames", video.getMaxFrames());
            put(result, "min_pixels", video.getMinPixels());
            put(result, "max_pixels", video.getMaxPixels());
            put(result, "total_pixels", video.getTotalPixels());
            return immutable(result);
        }
        if (block instanceof DataBlock data) {
            Map<String, Object> result = typed("data");
            put(result, "id", data.getId());
            put(result, "name", data.getName());
            result.put("source", mapSource(data.getSource(), state, depth + 1));
            return immutable(result);
        }
        if (block instanceof HintBlock hint) {
            Map<String, Object> result = typed("hint");
            put(result, "id", hint.getId());
            put(result, "hint", hint.getHint());
            put(result, "source", hint.getSource());
            return immutable(result);
        }
        state.complete = false;
        return Map.of(
                "type", block == null ? "unknown" : block.getClass().getSimpleName(),
                "unsupported", true);
    }

    private Map<String, Object> mapSource(
            Source source, MappingState state, int depth) {
        if (!state.enter(depth)) {
            return Map.of("kind", "unknown", "unsupported", true);
        }
        if (source instanceof URLSource url) {
            Map<String, Object> result = source("url");
            put(result, "url", url.getUrl());
            put(result, "mime_type", url.getMimeType());
            return immutable(result);
        }
        if (source instanceof Base64Source base64) {
            Map<String, Object> result = source("base64");
            put(result, "media_type", base64.getMediaType());
            put(result, "data", base64.getData());
            return immutable(result);
        }
        state.complete = false;
        return Map.of(
                "kind", source == null ? "unknown" : source.getClass().getSimpleName(),
                "unsupported", true);
    }

    private static Map<String, Object> typed(String type) {
        Map<String, Object> result = mutable();
        result.put("type", type);
        return result;
    }

    private static Map<String, Object> source(String kind) {
        Map<String, Object> result = mutable();
        result.put("kind", kind);
        return result;
    }

    private static Map<String, Object> mutable() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    record MappingResult(Map<String, Object> payload, boolean complete) {
        MappingResult {
            payload = immutable(payload);
        }
    }

    private static final class MappingState {
        private int nodes;
        private boolean complete = true;

        private boolean enter(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                complete = false;
                return false;
            }
            return true;
        }

        private Map<String, Object> boundedMap(Map<?, ?> source, int depth) {
            if (source == null) {
                return null;
            }
            if (!enter(depth)) {
                return Map.of();
            }
            Map<String, Object> bounded = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    complete = false;
                    break;
                }
                String key = String.valueOf(entry.getKey());
                bounded.put(key, boundedValue(entry.getValue(), depth + 1));
            }
            return immutable(bounded);
        }

        private Object boundedValue(Object value, int depth) {
            if (!enter(depth) || value == null) {
                return value;
            }
            if (value instanceof Map<?, ?> map) {
                return boundedMap(map, depth);
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> result = new ArrayList<>();
                for (Object item : iterable) {
                    if (result.size() >= MAX_COLLECTION_ITEMS) {
                        complete = false;
                        break;
                    }
                    result.add(boundedValue(item, depth + 1));
                }
                return Collections.unmodifiableList(result);
            }
            if (value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                int retained = Math.min(length, MAX_COLLECTION_ITEMS);
                if (length > retained) {
                    complete = false;
                }
                List<Object> result = new ArrayList<>(retained);
                for (int index = 0; index < retained; index++) {
                    result.add(boundedValue(java.lang.reflect.Array.get(value, index), depth + 1));
                }
                return Collections.unmodifiableList(result);
            }
            return value;
        }
    }
}
