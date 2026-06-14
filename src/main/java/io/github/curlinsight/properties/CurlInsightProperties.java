package io.github.curlinsight.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for curl-insight-spring-boot-starter.
 *
 * Example application.yml:
 * <pre>
 * curl-insight:
 *   enabled: true
 *   mode: always        # always | on-error
 *   mask-headers:
 *     - Authorization
 *   pretty-print-body: true
 *   log-file:
 *     path: logs/curl-insight.log
 *     rolling: daily
 *     max-history: 7
 * </pre>
 */
@ConfigurationProperties(prefix = "curl-insight")
public class CurlInsightProperties {

    /**
     * Master on/off switch. Default: true
     */
    private boolean enabled = true;

    /**
     * Logging mode:
     *   always   — log every outgoing request (at DEBUG)
     *   on-error — log only when response is 4xx or 5xx (at ERROR)
     * Default: always
     */
    private Mode mode = Mode.ALWAYS;

    public enum Mode {
        ALWAYS, ON_ERROR
    }

    /**
     * Headers whose values are replaced with ***masked*** in output.
     * Always case-insensitive match.
     */
    private List<String> maskHeaders = List.of(
        "Authorization",
        "z-auth-key",
        "c-token",
        "X-Auth-Token",
        "Cookie",
        "X-API-Key"
    );

    /**
     * Pretty-print JSON request bodies.
     * Requires jackson-databind on classpath.
     * Default: true
     */
    private boolean prettyPrintBody = true;

    /**
     * Truncate request bodies longer than this (chars).
     * Default: 2000
     */
    private int maxBodyLength = 2000;

    /**
     * Inject MDC correlationId as a header in curl output.
     * Default: true
     */
    private boolean includeCorrelationId = true;

    /**
     * MDC key to read correlation ID from.
     * Default: correlationId
     */
    private String correlationIdMdcKey = "correlationId";

    /**
     * URL patterns to exclude from curl logging.
     * Supports Ant-style patterns e.g. **\/actuator\/**
     * Default: actuator and health endpoints
     */
    private List<String> excludeUrls = List.of(
        "**/actuator/**",
        "**/health",
        "**/health/**"
    );

    /**
     * File logging config. If path is not set, curls go to console.
     */
    private LogFile logFile = new LogFile();

    public static class LogFile {

        /** File path. If blank, file logging is disabled. */
        private String path;

        /** Rolling strategy: daily, size, none. Default: daily */
        private String rolling = "daily";

        /** Max file size for size-based rolling. Default: 10MB */
        private String maxSize = "10MB";

        /** Number of rolled files/days to keep. Default: 7 */
        private int maxHistory = 7;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public String getRolling() { return rolling; }
        public void setRolling(String rolling) { this.rolling = rolling; }

        public String getMaxSize() { return maxSize; }
        public void setMaxSize(String maxSize) { this.maxSize = maxSize; }

        public int getMaxHistory() { return maxHistory; }
        public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }

        public boolean isEnabled() {
            return path != null && !path.isBlank();
        }
    }

    /**
     * OkHttp pool field name in internal HTTP client. Default: httpClientPool
     */
    private String okHttpPoolFieldName = "httpClientPool";

    /**
     * URL map field name in internal HTTP client. Default: restApiUrls
     */
    private String urlMapFieldName = "restApiUrls";

    // ── getters and setters ───────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public boolean isOnErrorMode() { return mode == Mode.ON_ERROR; }

    public List<String> getMaskHeaders() { return maskHeaders; }
    public void setMaskHeaders(List<String> maskHeaders) { this.maskHeaders = maskHeaders; }

    public boolean isPrettyPrintBody() { return prettyPrintBody; }
    public void setPrettyPrintBody(boolean prettyPrintBody) { this.prettyPrintBody = prettyPrintBody; }

    public int getMaxBodyLength() { return maxBodyLength; }
    public void setMaxBodyLength(int maxBodyLength) { this.maxBodyLength = maxBodyLength; }

    public boolean isIncludeCorrelationId() { return includeCorrelationId; }
    public void setIncludeCorrelationId(boolean v) { this.includeCorrelationId = v; }

    public String getCorrelationIdMdcKey() { return correlationIdMdcKey; }
    public void setCorrelationIdMdcKey(String key) { this.correlationIdMdcKey = key; }

    public List<String> getExcludeUrls() { return excludeUrls; }
    public void setExcludeUrls(List<String> excludeUrls) { this.excludeUrls = excludeUrls; }

    public LogFile getLogFile() { return logFile; }
    public void setLogFile(LogFile logFile) { this.logFile = logFile; }

    public String getOkHttpPoolFieldName() { return okHttpPoolFieldName; }
    public void setOkHttpPoolFieldName(String name) { this.okHttpPoolFieldName = name; }

    public String getUrlMapFieldName() { return urlMapFieldName; }
    public void setUrlMapFieldName(String name) { this.urlMapFieldName = name; }
}
