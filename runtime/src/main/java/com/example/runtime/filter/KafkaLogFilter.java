package com.example.runtime.filter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Sandbox analog of {@code kalix.runtime.eventing.kafka.KafkaLogFilter}.
 *
 * <p>The filter variety, with a configurable property: denies events from a
 * given logger-name prefix (the real one keeps Kafka client chatter off a
 * particular appender). Like {@link ThresholdRangeFilter}, a filter is not a
 * converter and has no supplier registry, and {@code <filter class="...">} would
 * resolve this runtime-only class via logback's own (SDK) classloader. So it is
 * attached programmatically — {@code new KafkaLogFilter()} + setter + start.</p>
 */
public class KafkaLogFilter extends Filter<ILoggingEvent> {

    private String deniedLoggerPrefix = "org.apache.kafka";

    public void setDeniedLoggerPrefix(String deniedLoggerPrefix) {
        this.deniedLoggerPrefix = deniedLoggerPrefix;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!isStarted()) {
            return FilterReply.NEUTRAL;
        }
        return event.getLoggerName().startsWith(deniedLoggerPrefix)
                ? FilterReply.DENY
                : FilterReply.NEUTRAL;
    }
}
