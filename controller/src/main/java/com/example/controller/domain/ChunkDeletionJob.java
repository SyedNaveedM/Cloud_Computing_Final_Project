package com.example.controller.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "chunk_deletion_job",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_deletion_job_chunk_worker",
                columnNames = {"chunk_id", "worker_id"}
        ),
        indexes = {
                @Index(
                        name = "idx_deletion_job_due",
                        columnList = "next_attempt_at, id"
                ),
                @Index(
                        name = "idx_deletion_job_object",
                        columnList = "object_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ChunkDeletionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    @Column(name = "object_id", length = 36)
    private String objectId;

    @Column(name = "chunk_id", nullable = false, length = 36)
    private String chunkId;

    @Column(name = "worker_id", nullable = false, length = 64)
    private String workerId;

    @Column(name = "worker_url", length = 255)
    private String workerUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}