# curl-insight-spring-boot-starter

Zero-config Spring Boot starter that auto-generates executable `curl` commands for every outgoing HTTP request in your application.

Add one dependency. Get instant curl output. Copy, paste, run.

---

## Installation

```xml
<dependency>
    <groupId>io.github.curlinsight</groupId>
    <artifactId>curl-insight-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Zero-config usage

That's it. Curls start printing at DEBUG level automatically.

```bash
[curl-insight]
curl -X POST \
  'https://user-service.internal/api/v1/details?lob=MOBILITY' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: ***masked***' \
  -H 'X-Correlation-Id: abc-123' \
  --data-raw '{"idType":"RTN","idValue":"xxx"}'
```

---

## Configuration

All properties are optional. Add to `application.yml`:

```yaml
curl-insight:
  enabled: true                   # master switch (default: true)
  log-level: DEBUG                # DEBUG/INFO/WARN (default: DEBUG)
  pretty-print-body: true         # format JSON body (default: true)
  max-body-length: 2000           # truncate body beyond this (default: 2000)
  include-correlation-id: true    # inject MDC correlationId (default: true)
  correlation-id-mdc-key: correlationId
  mask-headers:
    - Authorization
    - z-auth-key
    - c-token
    - Cookie
    - X-API-Key
  exclude-urls:
    - "**/actuator/**"
    - "**/health"
```

### Production safety

Set `log-level: DEBUG` (default). In production your log level is INFO,
so curls stay completely silent. During an incident, flip one service's
log level to DEBUG and get full curl output — no redeployment needed.

---

## Supported HTTP clients

| Client | Support | How |
|---|---|---|
| RestTemplate | Auto | BeanPostProcessor adds interceptor to all RestTemplate beans |
| OkHttp (via internal lib) | Manual | Extend `AbstractOkHttpAspect` (see below) |
| WebClient | Coming in 1.1.0 | ExchangeFilterFunction |
| Feign | Coming in 1.1.0 | feign.RequestInterceptor |

---

## OkHttp support (internal library)

If your org uses an internal HTTP library backed by OkHttp, extend
`AbstractOkHttpAspect` and provide a pointcut targeting your client:

```java
@Aspect
@Component
@ConditionalOnProperty(prefix = "curl-insight", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class InternalHttpClientAspect extends AbstractOkHttpAspect {

    public InternalHttpClientAspect(CurlInsightProperties props,
                                    CurlCommandBuilder builder) {
        super(props, builder);
    }

    @Around("target(com.yourorg.internal.http.YourHttpClient)")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.isEnabled()) return pjp.proceed();

        Object[] args   = pjp.getArgs();
        Object apiName  = args[0]; // your enum or string api identifier

        // inject OkHttp interceptor into pool for this api (once only)
        injectIfNeeded(pjp.getTarget(), apiName.toString());

        long start = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            log.debug("[curl-insight] {} took {}ms",
                apiName, System.currentTimeMillis() - start);
        }
    }
}
```

### Configuring field names

If your internal library uses different field names:

```yaml
curl-insight:
  ok-http-pool-field-name: httpClientPool   # default
  url-map-field-name: restApiUrls           # default
```

---

## How it works

```
Your service code
      ↓
Internal HTTP client bean   ← Spring AOP intercepts method call
      ↓
AbstractOkHttpAspect        ← uses reflection to find OkHttpClient
      ↓                        in pool map and inject interceptor (once)
OkHttpClient.execute()
      ↓
CurlPrintingInterceptor     ← fires with fully built request
      ↓                        including headers added by internal lib
Actual HTTP call goes out   ← unaffected, zero latency impact
```

**Why AOP + Reflection together?**
- AOP intercepts the Spring bean method call — gives us timing, api name, response class
- Reflection reaches inside the bean to find the OkHttpClient pool — gives us the fully assembled request with all headers (including Authorization added from internal config)
- OkHttp interceptor captures the final request — 100% accurate curl every time

