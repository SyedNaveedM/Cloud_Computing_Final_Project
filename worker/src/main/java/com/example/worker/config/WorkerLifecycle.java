package com.example.worker.config;

import com.example.common.dto.*;
import com.example.worker.storage.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "worker.controller-url")
public class WorkerLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WorkerLifecycle.class);
    private final ChunkStore store;
    private final RestClient client;
    private final String workerId;
    private final String advertisedUrl;
    private volatile boolean ready;
    private String token;

    public WorkerLifecycle(ChunkStore store,
            @Value("${worker.id:worker1}") String workerId,
            @Value("${worker.advertised-url}") String advertisedUrl,
            @Value("${worker.controller-url}") String controllerUrl) {
        this.store = store;
        this.workerId = workerId;
        this.advertisedUrl = advertisedUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(controllerUrl).requestFactory(factory).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready = true; }

    @Scheduled(fixedDelayString = "${worker.heartbeat-delay-ms:5000}", initialDelay = 1000)
    public synchronized void tick() {
        if (!ready) return;
        try {
            // This scan validates checksums; unreadable storage stops heartbeats.
            var inventory = store.listChunks();
            if (token == null) {
                var response = client.post().uri("/internal/workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new RegisterRequest(workerId, advertisedUrl, inventory))
                        .retrieve().toBodilessEntity();
                String received = response.getHeaders().getFirst("X-Registration-Token");
                if (response.getStatusCode().value() != 200 || received == null || received.isBlank())
                    throw new IllegalStateException("Registration did not return a token");
                token = received;
                log.info("Worker {} registered with {} healthy chunks", workerId, inventory.size());
            } else {
                long bytes = inventory.stream().mapToLong(ChunkInfo::size).sum();
                var response = client.post().uri("/internal/workers/heartbeat")
                        .header("X-Registration-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new HeartbeatRequest(workerId, bytes, inventory.size()))
                        .retrieve().toBodilessEntity();
                if (response.getStatusCode().value() != 204) throw new IllegalStateException("Unexpected heartbeat response");
            }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 409 || error.getStatusCode().value() == 404) token = null;
            log.warn("Worker {} will retry controller contact: HTTP {}", workerId, error.getStatusCode().value());
        } catch (Exception error) {
            log.warn("Worker {} will retry controller contact: {}", workerId, error.getMessage());
        }
    }
}
