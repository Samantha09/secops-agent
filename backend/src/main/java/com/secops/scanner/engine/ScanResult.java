package com.secops.scanner.engine;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ScanResult {
    private String scanner;
    private String target;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<Finding> findings;
    private Map<String, Object> rawOutput;
    private boolean success;
    private String errorMessage;

    @Data
    public static class Finding {
        private String id;
        private String name;
        private String severity;
        private String description;
        private String matched;
        private Map<String, Object> metadata;
    }
}
