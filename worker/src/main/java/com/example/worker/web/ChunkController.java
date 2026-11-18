package com.example.worker.web;

import com.example.worker.storage.ChunkStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.io.IOException;

@RestController
@RequestMapping("/internal/chunks")
public class ChunkController {

    private final ChunkStore store;

    public ChunkController(ChunkStore store) {
        this.store = store;
    }

    @PutMapping(value = "/{chunkId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> put(@PathVariable String chunkId,
                                    @RequestHeader(value = "X-Checksum", required = false) String checksum,
                                    HttpServletRequest request) throws IOException {
        boolean created = store.put(chunkId, request.getInputStream(), checksum);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/{chunkId}")
    public ResponseEntity<byte[]> get(@PathVariable String chunkId) throws IOException {
        ChunkStore.StoredChunk chunk = store.get(chunkId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(chunk.data().length)
                .header("X-Checksum", chunk.checksum())
                .body(chunk.data());
    }
    @DeleteMapping("/{chunkId}")
public ResponseEntity<Void> delete(
        @PathVariable String chunkId,
        @RequestParam(defaultValue = "true") boolean retire) throws IOException {

    if (retire) store.delete(chunkId);
    else store.evict(chunkId);
    return ResponseEntity.noContent().build();
}
}
