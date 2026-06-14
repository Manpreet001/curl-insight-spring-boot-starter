package io.github.curlinsight.autoconfigure;

import io.github.curlinsight.builder.CurlCommandBuilder;
import io.github.curlinsight.filter.WebClientCurlFilter;
import io.github.curlinsight.interceptor.FeignCurlErrorDecoder;
import io.github.curlinsight.interceptor.FeignCurlInterceptor;
import io.github.curlinsight.interceptor.RestTemplateCurlInterceptor;
import io.github.curlinsight.logging.CurlInsightFileAppender;
import io.github.curlinsight.properties.CurlInsightProperties;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Main autoconfiguration for curl-insight-spring-boot-starter.
 *
 * Activates when:
 * 1. curl-insight.enabled=true (default)
 * 2. The library is on the classpath
 *
 * Registers:
 * - CurlInsightProperties (config)
 * - CurlCommandBuilder (core curl builder)
 * - RestTemplateBeanPostProcessor (auto-adds interceptor to all RestTemplates)
 *
 * Consumer apps can extend AbstractOkHttpAspect to add support
 * for their specific internal OkHttp-based HTTP client.
 */
@AutoConfiguration
@EnableConfigurationProperties(CurlInsightProperties.class)
@ConditionalOnProperty(
    prefix = "curl-insight",
    name   = "enabled",
    havingValue = "true",
    matchIfMissing = true  // enabled by default
)
public class CurlInsightAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(CurlInsightAutoConfiguration.class);

    /**
     * Core curl builder — shared by all interceptors and aspects.
     */
    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CurlCommandBuilder curlCommandBuilder(CurlInsightProperties properties) {
        log.info("[curl-insight] CurlCommandBuilder initialized");
        return new CurlCommandBuilder(properties);
    }

    /**
     * File appender setup — only activated when Logback is on the classpath.
     * Guarded by @ConditionalOnClass so apps using Log4j2 or other
     * logging frameworks are completely unaffected.
     */
    @Configuration
    @ConditionalOnClass(LoggerContext.class)
    static class LogbackFileConfig {

        @Bean
        public Object curlInsightLogbackSetup(CurlInsightProperties properties) {
            if (properties.getLogFile().isEnabled()) {
                CurlInsightFileAppender.configure(properties);
                log.info("[curl-insight] File logging → {}",
                        properties.getLogFile().getPath());
            }
            return new Object(); // placeholder bean just to trigger this at startup
        }
    }

    /**
     * Auto-registers RestTemplateCurlInterceptor on every RestTemplate bean.
     */
    @Configuration
    @ConditionalOnClass(RestTemplate.class)
    static class RestTemplateConfig {

        @Bean
        public static BeanPostProcessor restTemplateCurlBeanPostProcessor(
                ObjectProvider<CurlCommandBuilder> builderProvider,
                ObjectProvider<CurlInsightProperties> propertiesProvider) {

            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(
                        Object bean, String beanName) throws BeansException {

                    if (bean instanceof RestTemplate restTemplate) {
                        // resolve lazily — avoids early bean initialization
                        CurlCommandBuilder builder = builderProvider.getIfAvailable();
                        CurlInsightProperties properties = propertiesProvider.getIfAvailable();
                        if (builder == null || properties == null) return bean;

                        List<org.springframework.http.client
                                .ClientHttpRequestInterceptor> interceptors =
                                new ArrayList<>(restTemplate.getInterceptors());
                        interceptors.add(
                                new RestTemplateCurlInterceptor(builder, properties));
                        restTemplate.setInterceptors(interceptors);
                        log.debug("[curl-insight] Registered on RestTemplate bean: {}",
                                beanName);
                    }
                    return bean;
                }
            };
        }
    }

    /**
     * Registers WebClientCurlFilter and applies it via WebClientCustomizer
     * so it auto-applies to the Spring Boot auto-configured WebClient.Builder.
     *
     * For manually constructed WebClient instances add the filter explicitly:
     *   WebClient.builder().filter(webClientCurlFilter).build()
     */
    @Configuration
    @ConditionalOnClass(WebClient.class)
    static class WebClientConfig {

        @Bean
        @ConditionalOnMissingBean
        public WebClientCurlFilter webClientCurlFilter(
                CurlCommandBuilder builder,
                CurlInsightProperties properties) {
            return new WebClientCurlFilter(builder, properties);
        }

        @Bean
        public WebClientCustomizer curlInsightWebClientCustomizer(
                WebClientCurlFilter filter) {
            return builder -> builder.filter(filter);
        }
    }

    /**
     * Registers FeignCurlInterceptor as a bean.
     * Spring Cloud OpenFeign automatically picks up all RequestInterceptor beans.
     */
    @Configuration
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignConfig {

        @Bean
        @ConditionalOnMissingBean
        public FeignCurlInterceptor feignCurlInterceptor(
                CurlCommandBuilder builder,
                CurlInsightProperties properties) {
            log.info("[curl-insight] FeignCurlInterceptor registered (mode={})",
                    properties.getMode());
            return new FeignCurlInterceptor(builder, properties);
        }

        /**
         * Registered only in on-error mode — reads curl from ThreadLocal
         * and logs it when Feign gets a 4xx/5xx response.
         */
        @Bean
        @ConditionalOnMissingBean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                prefix = "curl-insight", name = "mode", havingValue = "on-error")
        public FeignCurlErrorDecoder feignCurlErrorDecoder() {
            return new FeignCurlErrorDecoder();
        }
    }
}
