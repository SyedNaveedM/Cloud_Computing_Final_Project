package com.example.controller.client;

import com.example.controller.domain.Worker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@Profile("!fake")
public class HttpWorkerClient implements WorkerClient {

    private static final String CHECKSUM_HEADER = "X-Checksum";
    private static final String CHUNK_PATH = "/internal/chunks/{chunkId}";

    private final RestClient restClient;

    public HttpWorkerClient(RestClient workerRestClient) {
        this.restClient = workerRestClient;
    }

    @Override
    public void putChunk(Worker worker, String chunkId, byte[] data, String checksum) {
        try {
            restClient.put()
                    .uri(worker.getUrl() + CHUNK_PATH, chunkId)
                    .header(CHECKSUM_HEADER, checksum)
                    .body(data)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                throw new ChunkRejectedException(
                        "Worker " + worker.getWorkerId() + " rejected chunk " + chunkId + " (checksum mismatch)", e);
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
            restClient.delete()
                    .uri(worker.getUrl() + CHUNK_PATH, chunkId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Delete of chunk {} on worker {} failed (ignored, delete is best-effort): {}",
                    chunkId, worker.getWorkerId(), e.getMessage());
        }
    }
}
