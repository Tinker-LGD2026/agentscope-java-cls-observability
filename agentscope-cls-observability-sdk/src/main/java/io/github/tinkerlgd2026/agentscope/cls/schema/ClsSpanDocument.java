package io.github.tinkerlgd2026.agentscope.cls.schema;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A CLS span log together with the JSON nodes it was built from.
 *
 * <p>Carrying the nodes lets validation run without re-parsing the serialized attribute, resource,
 * link and log payloads, which removes four JSON parses per span from the export path.
 */
public record ClsSpanDocument(
        ClsSpanRecord record,
        ObjectNode attribute,
        ObjectNode resource,
        ArrayNode links,
        ArrayNode logs) {

    public ClsSpanDocument {
        if (record == null || attribute == null || resource == null || links == null || logs == null) {
            throw new IllegalArgumentException("record, attribute, resource, links and logs are required");
        }
    }
}
