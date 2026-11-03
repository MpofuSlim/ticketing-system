package com.innbucks.bookingservice.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.bookingservice.dto.BookingAddOnDTO;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/** A ticket's add-ons as a JSON array in a TEXT column (V33); none = NULL. */
@Converter
public class BookingAddOnsConverter implements AttributeConverter<List<BookingAddOnDTO>, String> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<BookingAddOnDTO>> LIST = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<BookingAddOnDTO> addOns) {
        if (addOns == null || addOns.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(addOns);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a ticket's add-ons", e);
        }
    }

    @Override
    public List<BookingAddOnDTO> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        try {
            List<BookingAddOnDTO> addOns = JSON.readValue(column, LIST);
            return addOns == null ? List.of() : List.copyOf(addOns);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A ticket's stored add-ons are not a JSON array", e);
        }
    }
}
