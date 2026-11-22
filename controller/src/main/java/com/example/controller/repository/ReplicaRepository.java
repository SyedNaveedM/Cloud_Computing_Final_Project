package com.example.controller.repository;

import com.example.controller.domain.Replica;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReplicaRepository extends JpaRepository<Replica, Long> {

    List<Replica> findByChunkId(String chunkId);

    Optional<Replica> findByChunkIdAndWorkerId(String chunkId, String workerId);

    void deleteByChunkId(String chunkId);
    List<Replica> findByWorkerId(String workerId);
}
