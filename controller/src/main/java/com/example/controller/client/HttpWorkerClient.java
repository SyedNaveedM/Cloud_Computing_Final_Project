package com.example.controller.client;

import com.example.controller.domain.Worker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.example.common.dto.ChunkInfo;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Objects;
@Slf4j
@Component
@Profile("!fake")
public class HttpWorkerClient implements WorkerClient {

    private static final String CHECKSUM_HEADER = "X-Checksum";
    private static final String CHUNK_PATH = "/internal/chunks/{chunkId}";

    private final RestClient restClient;

    public HttpWorkerClient(RestClient workerRestClient)
    {
        this.restClient = workerRestClient;
    }

    @Override
    public void putChunk(Worker worker, String chunkId, byte[] data, String checksum) {
        try {
            ResponseEntity<Void> response = restClient.put()
                    .uri(worker.getUrl() + CHUNK_PATH, chunkId)
                    .header(CHECKSUM_HEADER, checksum)
                    .body(data)
                    .retrieve()
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            if (status != 201 && status != 204) {
                throw new WorkerUnavailableException("Unexpected PUT status " + status);
            }
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();

if (status == 400 || status == 409) {
    throw new ChunkRejectedException(
            "Worker " + worker.getWorkerId()
                    + " rejected chunk " + chunkId
                    + " (HTTP " + status + ")",
            e);
}
            throw new WorkerUnavailableException(
                    "Worker " + worker.getWorkerId() + " returned " + e.getStatusCode() + " for PUT chunk " + chunkId, e);
        } catch (ResourceAccessException e) {
            throw new WorkerUnavailableException("Worker " + worker.getWorkerId() + " unreachable", e);
        }
    }

    @Override
    public byte[] getChunk(Worker worker, String chunkId) {
        try {
            return restClient.get()
                    .uri(worker.getUrl() + CHUNK_PATH, chunkId)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                throw new ChunkNotFoundException("Chunk " + chunkId + " not found on worker " + worker.getWorkerId());
            }
            if (status == 409) {
                throw new CorruptChunkException("Chunk " + chunkId + " reported corrupt by worker " + worker.getWorkerId());
            }
            throw new WorkerUnavailableException(
                    "Worker " + worker.getWorkerId() + " returned " + status + " for GET chunk " + chunkId, e);
        } catch (ResourceAccessException e) {
            throw new WorkerUnavailableException("Worker " + worker.getWorkerId() + " unreachable", e);
        }
    }

@Override
public void deleteChunk(Worker worker, String chunkId) {
    try {
        deleteChunkConfirmed(worker, chunkId);
    } catch (Exception error) {
        log.warn(
                "Best-effort deletion of chunk {} on worker {} failed: {}",
                chunkId,
                worker.getWorkerId(),
                error.getMessage());
    }
}

@Override
public void deleteChunkConfirmed(Worker worker, String chunkId) {
    try {
        ResponseEntity<Void> response = restClient.delete()
                .uri(worker.getUrl() + CHUNK_PATH, chunkId)
                .retrieve()
                .toBodilessEntity();

        if (response.getStatusCode().value() != 204) {
            throw new WorkerUnavailableException(
                    "Worker " + worker.getWorkerId()
                            + " returned unexpected status "
                            + response.getStatusCode().value()
                            + " for DELETE chunk " + chunkId);
        }
    } catch (RestClientException error) {
        throw new WorkerUnavailableException(
                "Could not confirm deletion of chunk "
                        + chunkId
                        + " on worker "
                        + worker.getWorkerId(),
                error);
    }
}
    @Override
public List<ChunkInfo> listChunks(Worker worker) {
    try {
        ResponseEntity<List<ChunkInfo>> response = restClient.get()
                .uri(
                        worker.getUrl()
                                + "/internal/workers/{workerId}/chunks",
                        worker.getWorkerId())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(
                        new ParameterizedTypeReference<List<ChunkInfo>>() {});

        if (response.getStatusCode().value() != 200) {
            throw new WorkerUnavailableException(
                    "Worker " + worker.getWorkerId()
                            + " returned unexpected inventory status "
                            + response.getStatusCode().value());
        }

        List<ChunkInfo> inventory = response.getBody();

        if (inventory == null
                || inventory.stream().anyMatch(Objects::isNull)) {
            throw new WorkerUnavailableException(
                    "Worker " + worker.getWorkerId()
                            + " returned a missing or invalid inventory");
        }

        return List.copyOf(inventory);
    } catch (RestClientException error) {
        throw new WorkerUnavailableException(
                "Could not fetch inventory from worker "
                        + worker.getWorkerId(),
                error);
    }
}

    @Override
    public void evictChunk(Worker worker, String chunkId) {
        try {
            var response = restClient.delete()
                    .uri(worker.getUrl() + CHUNK_PATH + "?retire=false", chunkId)
                    .retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 204) {
                throw new WorkerUnavailableException("Replica eviction was not confirmed");
            }
        } catch (RestClientException error) {
            throw new WorkerUnavailableException("Replica eviction failed", error);
        }
    }
}
