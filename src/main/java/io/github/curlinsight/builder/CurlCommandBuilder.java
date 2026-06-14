package io.github.curlinsight.builder;

import io.github.curlinsight.properties.CurlInsightProperties;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

/**
 * Core curl string builder.
 * All interceptors (OkHttp, RestTemplate, WebClient, Feign)
 * funnel through here — one place to change output format.
 */
public class CurlCommandBuilder {

    private final CurlInsightProperties properties;

    public CurlCommandBuilder(CurlInsightProperties properties) {
        this.properties = properties;
    }

    /**
     * Build curl from individual components.
     * Used by the AOP aspect when OkHttp pool injection is not possible.
     */
    public String build(
            String method,
            String url,
            Map<String, String> headers,
            String body,
            String apiName,
            String responseClassName) {

        StringBuilder curl = new StringBuilder();
        curl.append("curl -X ").append(method.toUpperCase()).append(" \\\n");
        curl.append("  '").append(url).append("'");

        // correlation ID from MDC
        if (properties.isIncludeCorrelationId()) {
            String correlationId = MDC.get(properties.getCorrelationIdMdcKey());
            if (correlationId != null && !correlationId.isBlank()) {
                curl.append(" \\\n")
                    .append("  -H 'X-Correlation-Id: ")
                    .append(correlationId).append("'");
            }
        }

        // headers
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                curl.append(" \\\n")
                    .append("  -H '")
                    .append(entry.getKey()).append(": ")
                    .append(maskIfNeeded(entry.getKey(), entry.getValue()))
                    .append("'");
            }
        }

        // body
        if (body != null && !body.isBlank()) {
            String formattedBody = formatBody(body);
            // truncate if too long
            if (formattedBody.length() > properties.getMaxBodyLength()) {
                formattedBody = formattedBody
                        .substring(0, properties.getMaxBodyLength())
                        + "...[truncated]";
            }
            curl.append(" \\\n")
                .append("  --data-raw '")
                .append(formattedBody.replace("'", "'\\''"))
                .append("'");
        }

        // metadata comments
        if (apiName != null) {
            curl.append("\n  # api     : ").append(apiName);
        }
        if (responseClassName != null) {
            curl.append("\n  # maps to : ").append(responseClassName);
        }

        return curl.toString();
    }

    /**
     * Build curl from OkHttp request headers map — simpler overload.
     * Used directly by CurlPrintingInterceptor.
     */
    public String buildFromHeaders(
            String method,
            String url,
            List<String> headerNames,
            java.util.function.Function<String, String> headerValueFn,
            String body) {

        StringBuilder curl = new StringBuilder();
        curl.append("curl -X ").append(method.toUpperCase()).append(" \\\n");
        curl.append("  '").append(url).append("'");

        // correlation ID from MDC
        if (properties.isIncludeCorrelationId()) {
            String correlationId = MDC.get(properties.getCorrelationIdMdcKey());
            if (correlationId != null && !correlationId.isBlank()) {
                curl.append(" \\\n")
                    .append("  -H 'X-Correlation-Id: ")
                    .append(correlationId).append("'");
            }
        }

        // headers from OkHttp request
        if (headerNames != null) {
            for (String name : headerNames) {
                String value = headerValueFn.apply(name);
                curl.append(" \\\n")
                    .append("  -H '")
                    .append(name).append(": ")
                    .append(maskIfNeeded(name, value))
                    .append("'");
            }
        }

        // body
        if (body != null && !body.isBlank()) {
            String trimmed = body.length() > properties.getMaxBodyLength()
                    ? body.substring(0, properties.getMaxBodyLength())
                           + "...[truncated]"
                    : body;
            curl.append(" \\\n")
                .append("  --data-raw '")
                .append(trimmed.replace("'", "'\\''"))
                .append("'");
        }

        return curl.toString();
    }

    /**
     * Convert Map params to JSON string without requiring Jackson.
     */
    public String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";

        // try Jackson first for proper formatting
        try {
            Class<?> mapperClass = Class.forName(
                "com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.getDeclaredConstructor().newInstance();
            if (properties.isPrettyPrintBody()) {
                java.lang.reflect.Method writerWithDefaultPP =
                    mapperClass.getMethod("writerWithDefaultPrettyPrinter");
                Object writer = writerWithDefaultPP.invoke(mapper);
                return (String) writer.getClass()
                    .getMethod("writeValueAsString", Object.class)
                    .invoke(writer, map);
            }
            return (String) mapperClass
                .getMethod("writeValueAsString", Object.class)
                .invoke(mapper, map);
        } catch (Exception ignored) {
            // Jackson not available — use simple builder
        }

        // fallback simple JSON builder
        StringBuilder json = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (i++ > 0) json.append(", ");
            json.append("\"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v == null)                             json.append("null");
            else if (v instanceof Number
                  || v instanceof Boolean)             json.append(v);
            else json.append("\"")
                     .append(v.toString().replace("\"", "\\\""))
                     .append("\"");
        }
        return json.append("}").toString();
    }

    // ── public helpers ────────────────────────────────────────────────────

    /**
     * Returns true when the URL matches any configured exclude pattern.
     * Ant-style: ** matches any path segment sequence, * matches within one segment.
     */
    public boolean shouldExclude(String url) {
        if (url == null) return false;
        return properties.getExcludeUrls().stream()
                .anyMatch(pattern -> matchesPattern(url, pattern));
    }

    private boolean matchesPattern(String url, String pattern) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*");
        return url.matches(".*" + regex + ".*");
    }

    // ── private helpers ───────────────────────────────────────────────────

    private String maskIfNeeded(String headerName, String value) {
        if (headerName == null || value == null) return value;
        boolean shouldMask = properties.getMaskHeaders().stream()
                .anyMatch(h -> h.equalsIgnoreCase(headerName));
        return shouldMask ? "***masked***" : value;
    }

    private String formatBody(String body) {
        if (!properties.isPrettyPrintBody()) return body;
        if (body == null || !body.trim().startsWith("{")) return body;
        try {
            Class<?> mapperClass = Class.forName(
                "com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.getDeclaredConstructor().newInstance();
            Object node = mapperClass
                .getMethod("readTree", String.class)
                .invoke(mapper, body);
            java.lang.reflect.Method pp =
                mapperClass.getMethod("writerWithDefaultPrettyPrinter");
            Object writer = pp.invoke(mapper);
            return (String) writer.getClass()
                .getMethod("writeValueAsString", Object.class)
                .invoke(writer, node);
        } catch (Exception ignored) {
            return body;
        }
    }
}
