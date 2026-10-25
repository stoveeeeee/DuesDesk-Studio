package com.fraternity.dues;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {
    private final Store store;

    public HealthController(Store store) {
        this.store = store;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean storageReady = store.isReadable();
        return ResponseEntity.status(storageReady ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", storageReady ? "UP" : "DEGRADED",
                        "storage", storageReady ? "available" : "unavailable",
                        "timestamp", Instant.now().toString()));
    }
}
