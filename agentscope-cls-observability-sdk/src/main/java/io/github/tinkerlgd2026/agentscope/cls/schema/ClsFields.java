package io.github.tinkerlgd2026.agentscope.cls.schema;

public final class ClsFields {
    public static final String SPAN_KIND = "gen_ai.span.kind";
    public static final String OPERATION_NAME = "gen_ai.operation.name";
    public static final String AGENT_TYPE = "gen_ai.agent.type";
    public static final String AGENT_ID = "gen_ai.agent.id";
    public static final String AGENT_NAME = "gen_ai.agent.name";
    public static final String SESSION_ID = "gen_ai.session.id";
    public static final String TURN_ID = "gen_ai.turn.id";
    public static final String STEP_ID = "gen_ai.step.id";
    public static final String USER_ID = "gen_ai.user.id";
    public static final String USER_NAME = "gen_ai.user.name";
    public static final String REASONING_PRESENT = "agentscope.reasoning.present";
    public static final String REASONING_BLOCK_COUNT = "agentscope.reasoning.block_count";
    public static final String REASONING_OUTPUT_BYTES = "agentscope.reasoning.output_bytes";
    public static final String REASONING_DURATION_MS = "agentscope.reasoning.duration_ms";
    public static final String REASONING_TTFT_MS =
            "agentscope.reasoning.time_to_first_token_ms";
    public static final String REASONING_CAPTURE_MODE =
            "agentscope.reasoning.capture_mode";
    public static final String REASONING_TRUNCATED = "agentscope.reasoning.truncated";
    public static final String REASONING_MALFORMED_EVENTS =
            "agentscope.reasoning.malformed_event_count";
    public static final String RESPONSE_TTFT_MS =
            "agentscope.response.time_to_first_token_ms";
    public static final String TURN_COMPLETED = "gen_ai.turn.completed";
    public static final String TURN_FINISH_REASON = "gen_ai.turn.finish_reason";
    public static final String INCOMPLETE = "gen_ai.incomplete";
    public static final String TURN_RESUME_FROM_TURN_ID = "gen_ai.turn.resume_from_turn_id";
    public static final String HITL_WAIT_COUNT = "gen_ai.hitl.wait_count";
    public static final String HITL_TOTAL_WAIT_MS = "gen_ai.hitl.total_wait_ms";
    public static final String PARTIAL_FAILURE = "gen_ai.partial_failure";
    public static final String FAILED_TOOL_COUNT = "gen_ai.failed_tool_count";
    public static final String CAPTURE_TRUNCATED = "agentscope.capture.truncated";
    public static final String CAPTURE_ORIGINAL_BYTES = "agentscope.capture.original_bytes";
    public static final String CAPTURE_RETAINED_BYTES = "agentscope.capture.retained_bytes";
    public static final String CAPTURE_CAPACITY_DROPPED_PARTS =
            "agentscope.capture.capacity_dropped_parts";
    public static final String CAPTURE_CAPACITY_DROPPED_BYTES =
            "agentscope.capture.capacity_dropped_bytes";
    public static final String CAPTURE_HASH_COMPLETE = "agentscope.capture.hash_complete";
    public static final String CAPTURE_FAILURE_COUNT = "agentscope.capture.failure_count";

    private ClsFields() {}
}
