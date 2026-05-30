package com.secops.scanner.engine;

import lombok.Data;

import java.util.concurrent.CompletableFuture;

/**
 * 扫描引擎统一接口
 * 所有扫描工具（Nuclei、Subfinder 等）通过适配器实现此接口
 */
public interface ScannerEngine {

    /**
     * 引擎名称
     */
    String getName();

    /**
     * 执行扫描
     * @param target 扫描目标（域名、IP、URL）
     * @param options 扫描选项
     * @return 异步扫描结果
     */
    CompletableFuture<ScanResult> scan(String target, ScanOptions options);

    /**
     * 验证引擎是否可用（已安装）
     */
    boolean isAvailable();

    @Data
    class ScanOptions {
        private int timeout = 300;
        private int threads = 25;
        private String templateFilter;
        private boolean headless = false;
    }
}
