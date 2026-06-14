package io.github.curlinsight.interceptor;

import feign.Response;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feign ErrorDecoder used in on-error mode.
 *
 * When a Feign call returns 4xx/5xx, this decoder reads the curl
 * command stored by FeignCurlInterceptor in ThreadLocal and logs it
 * at ERROR level, then delegates to the default error decoder.
 */
public class FeignCurlErrorDecoder implements ErrorDecoder {

    private static final Logger log =
            LoggerFactory.getLogger(FeignCurlErrorDecoder.class);

    private final ErrorDecoder delegate = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        try {
            String curl = FeignCurlInterceptor.CURL_HOLDER.get();
            if (curl != null) {
                log.error("\n[curl-insight] {} {}\n{}",
                        response.status(), response.request().url(), curl);
            }
        } finally {
            FeignCurlInterceptor.CURL_HOLDER.remove(); // always clean up
        }
        return delegate.decode(methodKey, response);
    }
}
