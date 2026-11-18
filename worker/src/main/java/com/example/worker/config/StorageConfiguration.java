package com.example.worker.config;

import com.example.worker.storage.ChunkStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class StorageConfiguration {

    @Bean(destroyMethod = "close")
    public ChunkStore chunkStore(
            @Value("${worker.storage-dir:./data/worker1}")
            String storageDirectory) throws IOException {

        return new ChunkStore(Path.of(storageDirectory));
    }
}