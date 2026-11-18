package com.example.controller.service;

import com.example.common.dto.ChunkInfo;
import com.example.controller.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistrationInventoryValidatorTest {

    @Test
    void rejectsDuplicateChunkIds() {
        var validator = new RegistrationInventoryValidator();
        var chunk = new ChunkInfo(
                UUID.randomUUID().toString(),
                3,
                "a".repeat(64));

        assertThrows(
                InvalidRequestException.class,
                () -> validator.validate(List.of(chunk, chunk)));
    }
}