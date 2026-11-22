package com.example.controller.repository;

import com.example.controller.domain.ChunkDeletionJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChunkDeletionJobRepository
        extends JpaRepository<ChunkDeletionJob, Long> {

    Optional<ChunkDeletionJob> findByChunkIdAndWorkerId(
            String chunkId,
            String workerId);

    List<ChunkDeletionJob> findByObjectId(String objectId);

    List<ChunkDeletionJob>
            findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                    Instant now);

    List<ChunkDeletionJob>
            findTop100ByObjectIdAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                    String objectId, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ChunkDeletionJob j where j.id = :jobId")
    Optional<ChunkDeletionJob> findByIdForUpdate(
            @Param("jobId") Long jobId);
}
