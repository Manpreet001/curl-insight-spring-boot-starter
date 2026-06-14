package io.github.curlinsight.filter;

import io.github.curlinsight.builder.CurlCommandBuilder;
import io.github.curlinsight.properties.CurlInsightProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * WebClient filter that logs outgoing requests as curl commands.
 *
 * mode: always   → logs every request at DEBUG
 * mode: on-error → logs only 4xx/5xx responses at ERROR
 */
public class WebClientCurlFilter implements ExchangeFilterFunction {

    private static final Logger log =
            LoggerFactory.getLogger(WebClientCurlFilter.class);

    private final CurlCommandBuilder builder;
    private final CurlInsightProperties properties;

    public WebClientCurlFilter(CurlCommandBuilder builder,
                                CurlInsightProperties properties) {
        this.builder    = builder;
        this.properties = properties;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        try {
            String url = request.url().toString();
            if (builder.shouldExclude(url)) {
                return next.exchange(request);
            }

            String curl = buildCurl(request, url);

            if (properties.isOnErrorMode()) {
                return next.exchange(request)
                        .doOnNext(response -> {
                            if (response.statusCode().is4xxClientError()
                                    || response.statusCode().is5xxServerError()) {
                                log.error("\n[curl-insight] {} {}\n{}",
                                        response.statusCode().value(), url, curl);
                            }
                        })
                        .doOnError(e -> log.error(
                                "\n[curl-insight] FAILED {} — {}\n{}",
                                url, e.getMessage(), curl));
            } else {
                log.debug("\n[curl-insight]\n{}", curl);
                return next.exchange(request);
            }

        } catch (Exception e) {
            log.warn("[curl-insight] WebClient curl failed: {}", e.getMessage());
            return next.exchange(request);
        }
    }

    private String buildCurl(ClientRequest request, String url) {
        List<String> headerNames = new ArrayList<>(request.headers().keySet());
        return builder.buildFromHeaders(
                request.method().name(),
                url,
                headerNames,
                name -> {
                    List<String> vals = request.headers().get(name);
                    return (vals != null && !vals.isEmpty())
                            ? String.join(", ", vals) : "";
                },
                null // body not available in reactive filter
        );
    }
}
