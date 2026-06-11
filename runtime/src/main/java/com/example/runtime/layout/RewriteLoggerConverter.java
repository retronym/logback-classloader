package com.example.runtime.layout;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Sandbox analog of {@code akka.runtime.logging.LogbackDevModeRewriteLogger}.
 *
 * <p>A <b>composite</b> {@code conversionRule} converter (the
 * {@code %rewriteLogger(...)} word): it wraps a child pattern and rewrites the
 * rendered child output — here stripping the noisy {@code com.example.} package
 * prefix, the kind of dev-mode tidy-up Akka applies to internal logger names.
 * Used as {@code %rewriteLogger(%logger)} in the pattern.</p>
 *
 * <p>Variety exercised: a {@link CompositeConverter} registered programmatically
 * as a {@code Supplier<DynamicConverter>} ({@code RewriteLoggerConverter::new}).
 * It travels through the <em>same</em> supplier registry as a simple converter,
 * but logback's {@code Compiler.createCompositeConverter} compiles a child
 * converter for it — so this proves the supplier hook covers the composite path
 * too, still with no name-based class loading.</p>
 */
public class RewriteLoggerConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return in == null ? "" : in.replace("com.example.", "");
    }
}
