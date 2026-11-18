package com.example.controller.service;

import com.example.common.Constants;
import com.example.common.dto.ChunkInfo;
import com.example.controller.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class RegistrationInventoryValidator {

    public Map<String, ChunkInfo> validate(List<ChunkInfo> reported) {
        if (reported == null) {
            throw new InvalidRequestException(
                    "Chunk inventory is required; use [] for an empty worker");
        }

        Map<String, ChunkInfo> inventory = new HashMap<>();

        for (ChunkInfo chunk : reported) {
            if (chunk == null) {
                throw new InvalidRequestException(
                        "Chunk inventory must not contain null entries");
            }

            UUID parsedId;
            try {
                parsedId = UUID.fromString(chunk.chunkId());
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new InvalidRequestException(
                        "Each chunk ID must be a canonical UUID");
            }

            String id = parsedId.toString();
            if (!id.equalsIgnoreCase(chunk.chunkId())) {
                throw new InvalidRequestException(
                        "Each chunk ID must be a canonical UUID");
            }

            if (chunk.size() < 1 || chunk.size() > Constants.CHUNK_SIZE) {
                throw new InvalidRequestException(
                        "Chunk size must be between 1 and "
                                + Constants.CHUNK_SIZE + " bytes");
            }

            if (chunk.checksum() == null
                    || !chunk.checksum().matches("[0-9a-fA-F]{64}")) {
                throw new InvalidRequestException(
                        "Each chunk checksum must be SHA-256 hexadecimal");
            }

            ChunkInfo normalized = new ChunkInfo(
                    id,
                    chunk.size(),
                    chunk.checksum().toLowerCase(Locale.ROOT));

            if (inventory.putIfAbsent(id, normalized) != null) {
                throw new InvalidRequestException(
                        "Duplicate chunk ID in inventory: " + id);
            }
        }

        return Map.copyOf(inventory);
    }
}