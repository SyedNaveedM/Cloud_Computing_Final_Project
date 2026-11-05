package com.example.controller.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Worker {

    @Id
    private String workerId;

    private String url;
    private Instant lastHeartbeat;

    @Enumerated(EnumType.STRING)
    private WorkerStatus status;

    private long usedBytes;
    private int chunkCount;
}
