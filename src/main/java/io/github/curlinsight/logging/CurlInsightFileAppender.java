package io.github.curlinsight.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import io.github.curlinsight.properties.CurlInsightProperties;
import org.slf4j.LoggerFactory;

/**
 * Programmatically configures a Logback FileAppender for the
 * io.github.curlinsight logger based on CurlInsightProperties.
 *
 * Called once at startup from CurlInsightAutoConfiguration.
 * No logback.xml changes needed by the consumer.
 */
public class CurlInsightFileAppender {

    public static final String LOGGER_NAME = "io.github.curlinsight";

    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n";

    public static void configure(CurlInsightProperties properties) {
        CurlInsightProperties.LogFile config = properties.getLogFile();
        if (!config.isEnabled()) return;

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger curlLogger = context.getLogger(LOGGER_NAME);

        // stop propagation to root logger (keeps curls out of console)
        curlLogger.setAdditive(false);
        curlLogger.setLevel(Level.DEBUG);

        Appender<ILoggingEvent> appender = buildAppender(context, config);
        appender.start();

        curlLogger.addAppender(appender);
    }

    private static Appender<ILoggingEvent> buildAppender(
            LoggerContext context,
            CurlInsightProperties.LogFile config) {

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.start();

        if ("none".equalsIgnoreCase(config.getRolling())) {
            FileAppender<ILoggingEvent> appender = new FileAppender<>();
            appender.setContext(context);
            appender.setName("CURL_INSIGHT_FILE");
            appender.setFile(config.getPath());
            appender.setAppend(true);
            appender.setEncoder(encoder);
            return appender;
        }

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName("CURL_INSIGHT_FILE");
        appender.setFile(config.getPath());
        appender.setEncoder(encoder);

        if ("size".equalsIgnoreCase(config.getRolling())) {
            configureSizeAndTimeRolling(appender, context, config);
        } else {
            configureDailyRolling(appender, context, config);
        }

        return appender;
    }

    private static void configureDailyRolling(
            RollingFileAppender<ILoggingEvent> appender,
            LoggerContext context,
            CurlInsightProperties.LogFile config) {

        TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(appender);
        // e.g. logs/curl-insight.2026-06-08.log
        policy.setFileNamePattern(config.getPath() + ".%d{yyyy-MM-dd}");
        policy.setMaxHistory(config.getMaxHistory());
        policy.start();

        appender.setRollingPolicy(policy);
    }

    private static void configureSizeAndTimeRolling(
            RollingFileAppender<ILoggingEvent> appender,
            LoggerContext context,
            CurlInsightProperties.LogFile config) {

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy =
                new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(config.getPath() + ".%d{yyyy-MM-dd}.%i");
        policy.setMaxHistory(config.getMaxHistory());
        policy.setMaxFileSize(FileSize.valueOf(config.getMaxSize()));
        policy.start();

        appender.setRollingPolicy(policy);
    }
}
