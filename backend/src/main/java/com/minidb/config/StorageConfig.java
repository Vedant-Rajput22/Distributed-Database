package com.minidb.config;

import com.minidb.storage.RocksDbEngine;
import com.minidb.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Value("${minidb.storage.path:data/rocksdb}")
    private String storagePath;

    @Bean
    public StorageEngine storageEngine() {
        return new RocksDbEngine(storagePath);
    }
}
