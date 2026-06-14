package io.github.curlinsight.interceptor;

import io.github.curlinsight.builder.CurlCommandBuilder;
import io.github.curlinsight.properties.CurlInsightProperties;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OkHttp interceptor that captures requests and logs them as curl commands.
 *
 * mode: always   → logs every request at DEBUG
 * mode: on-error → logs only 4xx/5xx responses at ERROR
 */
public class CurlPrintingInterceptor implements Interceptor {

    private static final Logger log =
            LoggerFactory.getLogger(CurlPrintingInterceptor.class);

    private final CurlCommandBuilder builder;
    private final CurlInsightProperties properties;

    public CurlPrintingInterceptor(CurlCommandBuilder builder,
                                   CurlInsightProperties properties) {
        this.builder    = builder;
        this.properties = properties;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // build curl first — always, regardless of mode or host reachability
        String curl = null;
        try {
            if (!builder.shouldExclude(request.url().toString())) {
                curl = buildCurl(request);
            }
        } catch (Exception e) {
            log.warn("[curl-insight] Failed to build curl: {}", e.getMessage());
        }

        // in always mode — log before the call
        if (curl != null && !properties.isOnErrorMode()) {
            log.debug("\n[curl-insight]\n{}", curl);
        }

        // proceed with the actual request
        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            // network failure (UnknownHostException, timeout, etc.)
            // log curl regardless of mode since no response will ever come
            if (curl != null) {
                log.error("\n[curl-insight] FAILED {} — {}\n{}",
                        request.url(), e.getMessage(), curl);
            }
            throw e; // rethrow so caller still gets the exception
        }

        // in on-error mode — log only on 4xx/5xx
        if (curl != null && properties.isOnErrorMode() && response.code() >= 400) {
            log.error("\n[curl-insight] {} {}\n{}",
                    response.code(), request.url(), curl);
        }

        return response;
    }

    private String buildCurl(Request request) throws IOException {
        List<String> headerNames = new ArrayList<>(request.headers().names());
        return builder.buildFromHeaders(
                request.method(),
                request.url().toString(),
                headerNames,
                name -> request.header(name),
                extractBody(request)
        );
    }

    private String extractBody(Request request) throws IOException {
        RequestBody requestBody = request.body();
        if (requestBody == null || requestBody.contentLength() == 0) return null;

        Buffer buffer = new Buffer();
        requestBody.writeTo(buffer);

        Charset charset = StandardCharsets.UTF_8;
        if (requestBody.contentType() != null
                && requestBody.contentType().charset() != null) {
            charset = requestBody.contentType().charset();
        }
        return buffer.readString(charset);
    }
}
