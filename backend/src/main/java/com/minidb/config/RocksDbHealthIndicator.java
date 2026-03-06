package com.minidb.config;

import com.minidb.storage.StorageEngine;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator health check for RocksDB.
 */
@Component
public class RocksDbHealthIndicator implements HealthIndicator {

    private final StorageEngine storage;

    public RocksDbHealthIndicator(StorageEngine storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        if (storage.isHealthy()) {
            return Health.up()
                    .withDetails(storage.getStats())
                    .build();
        }
        return Health.down()
                .withDetail("error", "RocksDB is not responsive")
                .build();
    }
}
