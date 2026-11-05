package com.example.controller.repository;

import com.example.controller.domain.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, String> {

    List<Chunk> findByObjectIdOrderByChunkIndex(String objectId);

    void deleteByObjectId(String objectId);
}
