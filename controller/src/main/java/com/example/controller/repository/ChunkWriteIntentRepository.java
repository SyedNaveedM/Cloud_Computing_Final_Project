package com.example.controller.repository;

import com.example.controller.domain.ChunkWriteIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChunkWriteIntentRepository
        extends JpaRepository<ChunkWriteIntent, Long> {

    List<ChunkWriteIntent> findByObjectId(String objectId);

    List<ChunkWriteIntent> findByChunkId(String chunkId);

    Optional<ChunkWriteIntent> findByChunkIdAndWorkerId(
            String chunkId,
            String workerId);
}