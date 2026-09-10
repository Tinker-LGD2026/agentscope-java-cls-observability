package io.github.tinkerlgd2026.agentscope.cls.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Creates the JSON mapper used by every SDK component.
 *
 * <p>The mapper is hardened for telemetry of untrusted agent payloads: unknown or empty beans never
 * raise errors, self references are tolerated, and both read and write nesting depth is bounded so
 * hostile or accidentally cyclic content cannot exhaust the stack of a business thread.
 */
public final class JsonSupport {
    private static final int MAX_NESTING_DEPTH = 64;

    private JsonSupport() {}

    public static ObjectMapper newObjectMapper() {
        JsonFactory factory =
                JsonFactory.builder()
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(MAX_NESTING_DEPTH)
                                        .build())
                        .streamWriteConstraints(
                                StreamWriteConstraints.builder()
                                        .maxNestingDepth(MAX_NESTING_DEPTH)
                                        .build())
                        .build();
        return JsonMapper.builder(factory)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.FAIL_ON_SELF_REFERENCES)
                .build();
    }
}
