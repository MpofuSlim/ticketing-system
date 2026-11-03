package com.innbucks.eventservice.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/** A list of strings kept in a TEXT column as a JSON array; null and empty are both {@code []}. */
@Converter
public class JsonStringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a string list as JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = JSON.readValue(column, LIST);
            return values == null ? new ArrayList<>() : new ArrayList<>(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored string list is not a JSON array", e);
        }
    }
}
