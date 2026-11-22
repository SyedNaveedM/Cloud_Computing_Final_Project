package com.example.controller.service;

import com.example.common.dto.ChunkInfo;
import com.example.controller.domain.Chunk;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkerRegistrationService {

    private final RegistrationInventoryValidator inventoryValidator;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;

    public WorkerRegistrationService(
            RegistrationInventoryValidator inventoryValidator,
            ChunkRepository chunkRepository,
            ReplicaRepository replicaRepository) {
        this.inventoryValidator = inventoryValidator;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
    }

    @Transactional(readOnly = true)
    public InventoryComparison compareInventory(
            String workerId, List<ChunkInfo> reported) {

        if (workerId == null || workerId.isBlank()) {
            throw new InvalidRequestException("Worker ID is required");
        }

        Map<String, ChunkInfo> inventory =
                inventoryValidator.validate(reported);

        Map<String, Chunk> knownChunks = new HashMap<>();
        for (Chunk chunk :
                chunkRepository.findAllById(inventory.keySet())) {
            knownChunks.put(chunk.getChunkId(), chunk);
        }

        Set<String> matching = new HashSet<>();
        Set<String> mismatched = new HashSet<>();
        Set<String> notReported = new HashSet<>();
        Set<String> unknown = new HashSet<>();

        for (ChunkInfo report : inventory.values()) {
            String chunkId = report.chunkId();
            Chunk expected = knownChunks.get(chunkId);

            if (expected == null) {
                unknown.add(chunkId);
            } else if (
                    report.size() == expected.getSize()
                    && report.checksum().equalsIgnoreCase(
                            expected.getChecksum())) {
                matching.add(chunkId);
            } else {
                mismatched.add(chunkId);
            }
        }

        for (var replica :
                replicaRepository.findByWorkerId(workerId)) {
            if (!inventory.containsKey(replica.getChunkId())) {
                notReported.add(replica.getChunkId());
            }
        }

        return new InventoryComparison(
                Set.copyOf(matching),
                Set.copyOf(mismatched),
                Set.copyOf(notReported),
                Set.copyOf(unknown));
    }

    public record InventoryComparison(
            Set<String> matching,
            Set<String> mismatched,
            Set<String> notReported,
            Set<String> unknown) {
    }
}