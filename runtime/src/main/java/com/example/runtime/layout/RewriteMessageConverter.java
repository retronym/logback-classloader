package com.example.runtime.layout;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Sandbox analog of {@code akka.runtime.logging.LogbackDevModeRewriteMessage}.
 *
 * <p>A <b>simple</b> {@code conversionRule} converter (the {@code %rewriteMsg}
 * word): it rewrites the formatted message, here redacting anything that looks
 * like a secret ({@code password=...} / {@code token=...}).</p>
 *
 * <p>Variety exercised: a {@link ClassicConverter} registered programmatically
 * as a {@code Supplier<DynamicConverter>} ({@code RewriteMessageConverter::new}),
 * so logback's {@code Compiler.createConverter} resolves it with
 * {@code supplier.get()} — no {@code Class.forName} across the CL boundary.</p>
 */
public class RewriteMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        return msg == null ? "" : msg.replaceAll("(?i)\\b(password|token)=\\S+", "$1=***");
    }
}
