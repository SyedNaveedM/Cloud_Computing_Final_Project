package com.example.controller.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "chunk_write_intent",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_write_intent_chunk_worker",
                columnNames = {"chunk_id", "worker_id"}
        ),
        indexes = @Index(
                name = "idx_write_intent_object",
                columnList = "object_id"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class ChunkWriteIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_id", nullable = false, length = 36)
    private String objectId;

    @Column(name = "chunk_id", nullable = false, length = 36)
    private String chunkId;

    @Column(name = "worker_id", nullable = false, length = 64)
    private String workerId;

    @Column(name = "worker_url", nullable = false, length = 255)
    private String workerUrl;

    @Column(name = "registration_token", nullable = false, length = 36)
    private String registrationToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}