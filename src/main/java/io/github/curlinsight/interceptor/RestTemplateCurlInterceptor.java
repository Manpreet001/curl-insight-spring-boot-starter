package io.github.curlinsight.interceptor;

import io.github.curlinsight.builder.CurlCommandBuilder;
import io.github.curlinsight.properties.CurlInsightProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RestTemplate interceptor that logs outgoing requests as curl commands.
 *
 * mode: always   → logs every request at DEBUG
 * mode: on-error → logs only 4xx/5xx responses at ERROR
 */
public class RestTemplateCurlInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(RestTemplateCurlInterceptor.class);

    private final CurlCommandBuilder builder;
    private final CurlInsightProperties properties;

    public RestTemplateCurlInterceptor(CurlCommandBuilder builder,
                                       CurlInsightProperties properties) {
        this.builder    = builder;
        this.properties = properties;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution)
            throws IOException {

        String url = request.getURI().toString();

        // build curl first — always, regardless of mode or host reachability
        String curl = null;
        try {
            if (!builder.shouldExclude(url)) {
                curl = buildCurl(request, body, url);
            }
        } catch (Exception e) {
            log.warn("[curl-insight] Failed to build curl: {}", e.getMessage());
        }

        // in always mode — log before the call
        if (curl != null && !properties.isOnErrorMode()) {
            log.debug("\n[curl-insight]\n{}", curl);
        }

        // proceed with the actual request
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException e) {
            // network failure — log curl since no response will ever come
            if (curl != null) {
                log.error("\n[curl-insight] FAILED {} — {}\n{}",
                        url, e.getMessage(), curl);
            }
            throw e;
        }

        // in on-error mode — log only on 4xx/5xx
        if (curl != null && properties.isOnErrorMode()
                && (response.getStatusCode().is4xxClientError()
                    || response.getStatusCode().is5xxServerError())) {
            log.error("\n[curl-insight] {} {}\n{}",
                    response.getStatusCode().value(), url, curl);
        }

        return response;
    }

    private String buildCurl(HttpRequest request, byte[] body, String url) {
        String bodyStr = (body != null && body.length > 0)
                ? new String(body, StandardCharsets.UTF_8) : null;

        List<String> headerNames = new ArrayList<>(request.getHeaders().keySet());

        return builder.buildFromHeaders(
                request.getMethod().name(),
                url,
                headerNames,
                name -> {
                    List<String> vals = request.getHeaders().get(name);
                    return (vals != null && !vals.isEmpty())
                            ? String.join(", ", vals) : "";
                },
                bodyStr
        );
    }
}
