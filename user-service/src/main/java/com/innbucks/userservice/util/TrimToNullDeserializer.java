package com.innbucks.userservice.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Trims an incoming JSON string and maps blank to {@code null}, so leading /
 * trailing whitespace never reaches bean validation or storage:
 * {@code " John "} -> {@code "John"}, {@code "   "} -> {@code null} (so a
 * {@code @NotBlank} field reports "required" rather than sneaking past on
 * whitespace, and an optional field is treated as absent).
 *
 * <p>Applied per-field via {@code @JsonDeserialize} on the DTO — deliberately
 * NOT registered globally, so secret-bearing fields (passwords, tokens) are
 * never silently trimmed.
 */
public class TrimToNullDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getValueAsString();
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
