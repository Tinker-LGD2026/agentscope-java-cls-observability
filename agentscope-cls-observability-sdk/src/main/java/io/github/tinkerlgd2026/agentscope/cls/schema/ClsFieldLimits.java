package io.github.tinkerlgd2026.agentscope.cls.schema;

/** Fixed CLS field and record byte limits (UTF-8 encoded). */
public final class ClsFieldLimits {
    /** Physical CLS single-field limit; configured attribute budgets may be smaller. */
    public static final int ATTRIBUTE_FIELD_MAX_BYTES = 1_000_000;
    /** Default configured attribute budget applied by the bounded encoder. */
    public static final int DEFAULT_ATTRIBUTE_MAX_BYTES = 950_000;
    public static final int RESOURCE_MAX_BYTES = 65_536;
    public static final int LINKS_MAX_BYTES = 131_072;
    public static final int LOGS_MAX_BYTES = 262_144;
    public static final int NAME_MAX_BYTES = 4_096;
    public static final int STATUS_MESSAGE_MAX_BYTES = 4_096;
    public static final int TRACE_STATE_MAX_BYTES = 4_096;
    /** Hard upper bound for one encoded span record (1.5 MiB). */
    public static final int RECORD_MAX_BYTES = 1_572_864;

    private ClsFieldLimits() {}
}
