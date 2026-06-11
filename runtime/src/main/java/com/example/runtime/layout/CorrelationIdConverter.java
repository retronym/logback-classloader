package com.example.runtime.layout;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Emits the MDC {@code correlationId} (or {@code -} when absent) as a pattern
 * conversion word — the data half of the old {@link CorrelationIdLayout}.
 *
 * <p>This lives in runtime.jar (Runtime CL), the same place logback (in the
 * parent SDK CL) historically could not reach.  The difference: nobody asks
 * logback to load this class by name.  Runtime code registers it with logback
 * as a {@code Supplier<DynamicConverter>} — a constructor reference
 * ({@code CorrelationIdConverter::new}) — so logback's {@code Compiler} simply
 * calls {@code supplier.get()}.  No {@code Class.forName}, no
 * {@code Loader.loadClass}, hence no cross-classloader lookup to fail.</p>
 */
public class CorrelationIdConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return event.getMDCPropertyMap().getOrDefault("correlationId", "-");
    }
}
