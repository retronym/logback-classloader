package com.example.runtime.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Custom layout that prepends a MDC correlation-id to each log line.
 *
 * Lives in runtime.jar (Runtime ClassLoader).  Referenced by FQN in
 * logback.xml which is loaded by logback (SDK ClassLoader).  This is the
 * core of the classloader challenge: logback must reach "down" into a child
 * CL to instantiate this class.  The fix: set TCCL to App CL before logback
 * initialises; Joran uses TCCL for class instantiation.
 */
public class CorrelationIdLayout extends LayoutBase<ILoggingEvent> {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @Override
    public String doLayout(ILoggingEvent event) {
        String cid = event.getMDCPropertyMap().getOrDefault("correlationId", "-");
        return String.format("[%s] [%s] [%-5s] [%s] %s%n",
                FMT.format(Instant.ofEpochMilli(event.getTimeStamp())),
                cid,
                event.getLevel(),
                event.getLoggerName(),
                event.getFormattedMessage());
    }
}
