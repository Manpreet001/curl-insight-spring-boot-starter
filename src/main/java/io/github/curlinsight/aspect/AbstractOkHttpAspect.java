package io.github.curlinsight.aspect;

import io.github.curlinsight.builder.CurlCommandBuilder;
import io.github.curlinsight.interceptor.CurlPrintingInterceptor;
import io.github.curlinsight.properties.CurlInsightProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Base class containing all OkHttp pool injection logic via reflection.
 *
 * Extend this class and provide a concrete @Around pointcut
 * targeting your specific internal HTTP client bean.
 *
 * The injection happens ONCE per unique API name (enum key),
 * not on every call — subsequent calls reuse the injected interceptor.
 *
 * Example extension:
 * <pre>
 * {@literal @}Aspect
 * {@literal @}Component
 * {@literal @}ConditionalOnProperty(prefix = "curl-insight", name = "enabled",
 *                         havingValue = "true", matchIfMissing = true)
 * public class MyHttpClientAspect extends AbstractOkHttpAspect {
 *
 *     public MyHttpClientAspect(CurlInsightProperties props,
 *                               CurlCommandBuilder builder) {
 *         super(props, builder);
 *     }
 *
 *     {@literal @}Around("target(com.myorg.internal.http.MyHttpClient)")
 *     public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
 *         return handleIntercept(pjp);
 *     }
 * }
 * </pre>
 */
public abstract class AbstractOkHttpAspect {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final CurlInsightProperties properties;
    protected final CurlCommandBuilder builder;

    // tracks which pool keys already have our interceptor injected
    // synchronized for thread safety
    private final Set<String> injectedKeys =
            Collections.synchronizedSet(new HashSet<>());

    protected AbstractOkHttpAspect(
            CurlInsightProperties properties,
            CurlCommandBuilder builder) {
        this.properties = properties;
        this.builder    = builder;
    }

    /**
     * Inject CurlPrintingInterceptor into the OkHttpClient
     * associated with the given API name key in the pool map.
     *
     * Uses the field names configured in CurlInsightProperties:
     * - curl-insight.ok-http-pool-field-name (default: httpClientPool)
     * - curl-insight.url-map-field-name       (default: restApiUrls)
     *
     * @param target  the internal HTTP client bean
     * @param apiName the enum/string key identifying the API
     */
    protected void injectIfNeeded(Object target, String apiName) {
        if (!properties.isEnabled()) return;
        if (injectedKeys.contains(apiName)) return; // already injected

        try {
            // unwrap CGLIB proxy to get the real target object
            target = unwrapProxy(target);

            Field poolField = findField(target,
                    properties.getOkHttpPoolFieldName());
            if (poolField == null) {
                log.warn("[curl-insight] Pool field '{}' not found in {}",
                        properties.getOkHttpPoolFieldName(),
                        target.getClass().getSimpleName());
                return;
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> pool =
                    (Map<Object, Object>) poolField.get(target);

            if (pool == null || pool.isEmpty()) {
                log.warn("[curl-insight] Pool map is null or empty");
                return;
            }

            // find the OkHttpClient entry for this api name
            Object poolKey   = null;
            OkHttpClient existing = null;

            for (Map.Entry<Object, Object> entry : pool.entrySet()) {
                if (entry.getKey().toString().equalsIgnoreCase(apiName)
                        && entry.getValue() instanceof OkHttpClient) {
                    poolKey  = entry.getKey();
                    existing = (OkHttpClient) entry.getValue();
                    break;
                }
            }

            // fallback — use first available OkHttpClient
            if (existing == null) {
                for (Map.Entry<Object, Object> entry : pool.entrySet()) {
                    if (entry.getValue() instanceof OkHttpClient) {
                        poolKey  = entry.getKey();
                        existing = (OkHttpClient) entry.getValue();
                        log.debug("[curl-insight] No exact key match for '{}', "
                                + "using fallback key '{}'", apiName, poolKey);
                        break;
                    }
                }
            }

            if (existing == null) {
                log.warn("[curl-insight] No OkHttpClient found in pool "
                        + "for api: {}", apiName);
                return;
            }

            // patch: add our interceptor to a copy of the client
            OkHttpClient patched = existing.newBuilder()
                    .addInterceptor(new CurlPrintingInterceptor(
                            builder, properties))
                    .build();

            // replace entry in pool
            pool.put(poolKey, patched);

            injectedKeys.add(apiName);
            log.debug("[curl-insight] Injected into pool[{}] successfully",
                    apiName);

        } catch (Exception e) {
            log.warn("[curl-insight] Pool injection failed for {}: {}",
                    apiName, e.getMessage());
        }
    }

    /**
     * Read URL from the restApiUrls map for the given api name.
     *
     * @param target  the internal HTTP client bean
     * @param apiName the enum/string key
     * @return the URL string or "UNKNOWN_URL"
     */
    protected String resolveUrl(Object target, String apiName) {
        try {
            Field urlField = findField(target,
                    properties.getUrlMapFieldName());
            if (urlField == null) return "UNKNOWN_URL";

            Map<?, ?> urls = (Map<?, ?>) urlField.get(target);
            if (urls == null) return "UNKNOWN_URL";

            // direct lookup
            Object val = urls.get(apiName);
            if (val != null) return val.toString();

            // case-insensitive fallback
            for (Map.Entry<?, ?> e : urls.entrySet()) {
                if (e.getKey().toString().equalsIgnoreCase(apiName)
                        && e.getValue() != null) {
                    return e.getValue().toString();
                }
            }

        } catch (Exception e) {
            log.warn("[curl-insight] resolveUrl failed: {}", e.getMessage());
        }
        return "UNKNOWN_URL";
    }

    /**
     * Unwrap CGLIB/Spring proxy to get the real underlying target object.
     * Reflection on a proxy won't find fields declared in the real class.
     */
    protected Object unwrapProxy(Object target) {
        try {
            // Spring AOP proxy
            if (target instanceof org.springframework.aop.framework.Advised advised) {
                return advised.getTargetSource().getTarget();
            }
        } catch (Exception ignored) { }

        try {
            // CGLIB proxy — check if it has a CGLIB$CALLBACK field pointing to target
            if (target.getClass().getName().contains("$$EnhancerBySpringCGLIB$$")
                    || target.getClass().getName().contains("$$SpringCGLIB$$")) {
                Field callbackField = findField(target, "CGLIB$CALLBACK_0");
                if (callbackField != null) {
                    Object callback = callbackField.get(target);
                    if (callback != null) {
                        Field advisedField = findField(callback, "advised");
                        if (advisedField != null) {
                            Object advised = advisedField.get(callback);
                            if (advised instanceof org.springframework.aop.framework.AdvisedSupport as) {
                                return as.getTargetSource().getTarget();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        return target; // return as-is if unwrapping fails
    }

    /**
     * Walk class hierarchy to find a declared field by name.
     * Sets accessible = true before returning.
     */
    protected Field findField(Object target, String fieldName) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Safe toString with truncation — never lets a bad
     * toString() bubble up and break the aspect.
     */
    protected String safeToString(Object obj) {
        if (obj == null) return "null";
        try {
            String s = obj.toString();
            return s.length() > 300
                    ? s.substring(0, 300) + "...[truncated]" : s;
        } catch (Exception e) {
            return "[toString() failed]";
        }
    }
}
