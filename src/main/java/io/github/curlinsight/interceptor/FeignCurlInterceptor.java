package io.github.curlinsight.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.curlinsight.builder.CurlCommandBuilder;
import io.github.curlinsight.properties.CurlInsightProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Feign RequestInterceptor that logs outgoing requests as curl commands.
 *
 * mode: always   → logs every request at DEBUG
 * mode: on-error → stores curl in ThreadLocal; FeignCurlErrorDecoder
 *                  reads it and logs at ERROR on 4xx/5xx
 */
public class FeignCurlInterceptor implements RequestInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(FeignCurlInterceptor.class);

    /**
     * ThreadLocal used in on-error mode to carry the curl string
     * from the request interceptor to the error decoder.
     */
    static final ThreadLocal<String> CURL_HOLDER = new ThreadLocal<>();

    private final CurlCommandBuilder builder;
    private final CurlInsightProperties properties;

    public FeignCurlInterceptor(CurlCommandBuilder builder,
                                CurlInsightProperties properties) {
        this.builder    = builder;
        this.properties = properties;
    }

    @Override
    public void apply(RequestTemplate template) {
        try {
            String url = resolveUrl(template);
            if (builder.shouldExclude(url)) return;

            List<String> headerNames = new ArrayList<>(template.headers().keySet());
            String curl = builder.buildFromHeaders(
                    template.method(),
                    url,
                    headerNames,
                    name -> joinValues(template.headers().get(name)),
                    extractBody(template)
            );

            if (properties.isOnErrorMode()) {
                // store for FeignCurlErrorDecoder to log on failure
                CURL_HOLDER.set(curl);
            } else {
                log.debug("\n[curl-insight]\n{}", curl);
            }
        } catch (Exception e) {
            log.warn("[curl-insight] Feign curl failed: {}", e.getMessage());
        }
    }

    private String resolveUrl(RequestTemplate template) {
        String path = template.url();
        try {
            if (template.feignTarget() != null) {
                return template.feignTarget().url() + path;
            }
        } catch (Exception ignored) { }
        return path;
    }

    private String extractBody(RequestTemplate template) {
        byte[] body = template.body();
        if (body == null || body.length == 0) return null;
        return new String(body, StandardCharsets.UTF_8);
    }

    private String joinValues(Collection<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(", ", values);
    }
}
