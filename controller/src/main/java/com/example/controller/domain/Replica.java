package com.example.controller.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Replica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String chunkId;
    private String workerId;

    @Enumerated(EnumType.STRING)
    private ReplicaState state;

    public Replica(String chunkId, String workerId, ReplicaState state) {
        this.chunkId = chunkId;
        this.workerId = workerId;
        this.state = state;
    }
}
