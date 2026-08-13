package com.straycat.statistra.dto.query;

/**
 * What a breakdown groups by: either the event type column, or a key inside the
 * metadata document.
 *
 * @param metadataKey the metadata key when {@code type} is {@code METADATA},
 *                    otherwise null. Always validated by
 *                    {@link MetadataFilter#validateKey(String)} before reaching here.
 */
public record BreakdownDimension(Type type, String metadataKey) {

    public enum Type {
        EVENT_TYPE,
        METADATA
    }

    public static BreakdownDimension eventType() {
        return new BreakdownDimension(Type.EVENT_TYPE, null);
    }

    /**
     * Parses the {@code groupBy} parameter. {@code eventType} selects the column;
     * anything prefixed {@code metadata.} selects a key within the document.
     */
    public static BreakdownDimension from(String groupBy) {
        if (groupBy == null || groupBy.isBlank() || groupBy.equalsIgnoreCase("eventType")) {
            return eventType();
        }
        if (groupBy.startsWith("metadata.")) {
            String key = groupBy.substring("metadata.".length());
            return new BreakdownDimension(Type.METADATA, MetadataFilter.validateKey(key));
        }
        throw new IllegalArgumentException(
                "Unknown groupBy '" + groupBy + "'. Expected 'eventType' or 'metadata.<key>'");
    }
}
