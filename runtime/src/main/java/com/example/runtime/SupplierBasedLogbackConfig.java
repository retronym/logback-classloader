package com.example.runtime;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.pattern.DynamicConverter;
import ch.qos.logback.core.status.WarnStatus;

import com.example.runtime.filter.KafkaLogFilter;
import com.example.runtime.filter.ThresholdRangeFilter;
import com.example.runtime.layout.CorrelationIdConverter;
import com.example.runtime.layout.DefaultJsonFormatter;
import com.example.runtime.layout.JsonFormatter;
import com.example.runtime.layout.JsonLayout;
import com.example.runtime.layout.RewriteLoggerConverter;
import com.example.runtime.layout.RewriteMessageConverter;

import org.slf4j.LoggerFactory;

/**
 * Solution I — register what can't cross the classloader boundary
 * programmatically, then drive the rest of the configuration from XML.
 *
 * <p>The other solutions in this repo attack the root cause from the pull side:
 * logback (in a parent CL) tries to {@code Class.forName} a class named in
 * {@code logback.xml} that only exists in a child CL, so they bend the
 * classloader graph (custom Layer-0 CL, agent rewrite, system-CL replacement,
 * self-attach) until logback's CL can see downward.</p>
 *
 * <p>Here the Runtime layer holds direct references to its own classes, so it
 * supplies them rather than letting logback resolve them by name. The real
 * system has three varieties of custom class; each is handled by the mechanism
 * that fits it:</p>
 *
 * <table border="1">
 *   <caption>How each custom-class variety is configured</caption>
 *   <tr><th>Variety</th><th>Sandbox class</th><th>Mechanism</th></tr>
 *   <tr><td>{@code conversionRule} (simple)</td><td>{@link RewriteMessageConverter}</td>
 *       <td>Supplier hook → usable from the XML pattern as {@code %rewriteMsg}</td></tr>
 *   <tr><td>{@code conversionRule} (composite)</td><td>{@link RewriteLoggerConverter}</td>
 *       <td>Supplier hook → {@code %rewriteLogger(%logger)}; compiles a child converter</td></tr>
 *   <tr><td>custom {@code <layout>} + nested component</td><td>{@link JsonLayout} + {@link JsonFormatter}</td>
 *       <td>Programmatic — no layout supplier registry exists</td></tr>
 *   <tr><td>custom {@code <filter>}</td><td>{@link ThresholdRangeFilter}, {@link KafkaLogFilter}</td>
 *       <td>Programmatic — no filter supplier registry exists</td></tr>
 * </table>
 *
 * <p>Converters are the lucky case: logback 1.5.13+ resolves them through a
 * {@code Supplier<DynamicConverter>} registry, so a constructor reference avoids
 * {@code Class.forName} entirely and the words can then be used from ordinary XML
 * patterns. Layouts and filters have no such registry — {@code <layout class=...>}
 * / {@code <filter class=...>} would resolve the class via the {@code LoggerContext}'s
 * (SDK) classloader (no TCCL fallback in 1.5.x) — so those are built with plain
 * {@code new} here.</p>
 *
 * <h2>Thread-safety note</h2>
 * <p>The tempting one-liner {@code PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put(...)}
 * is <em>not</em> safe: that field is a plain {@code static HashMap} and
 * {@code PatternLayoutBase.getEffectiveConverterMap()} reads it with
 * {@code putAll} under no lock — a concurrent config on another thread races a
 * runtime mutation. We instead publish into the <em>context</em> registry; the
 * context object map is a {@code ConcurrentHashMap}, so the reference is published
 * safely. The discipline is: build the inner map fully, publish it once, never
 * mutate it again.</p>
 */
public final class SupplierBasedLogbackConfig {

    private SupplierBasedLogbackConfig() {
    }

    private static final String APP_FRAGMENT_PROPERTY = "appFragmentUrl";

    public static void configure(ClassLoader appCL) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.reset();

        // ── 1. Supplier hook: every custom conversionRule, as a constructor ref ──
        // Fills the same registry slot that <conversionRule> populates, but with
        // real constructor references instead of class-name strings. Built fully,
        // published once (safe publication via the context's ConcurrentHashMap),
        // never mutated afterwards. logback.xml's pattern words resolve here.
        // Pre-registering also sidesteps the document-order rule the real config
        // notes ("conversionRule must precede any appender that uses the word") —
        // the words exist before any appender starts.
        Map<String, Supplier<DynamicConverter>> rules = new HashMap<>();
        rules.put("correlationId", CorrelationIdConverter::new);   // simple
        rules.put("rewriteMsg", RewriteMessageConverter::new);     // simple
        rules.put("rewriteLogger", RewriteLoggerConverter::new);   // composite
        ctx.putObject(CoreConstants.PATTERN_RULE_REGISTRY_FOR_SUPPLIERS, rules);

        // ── 2. Publish the app fragment URL for <include url="${appFragmentUrl}"> ─
        // Resolved via appCL (which we hold) rather than logback's own CL.
        URL appFragmentUrl = appCL.getResource("logback-app.xml");
        if (appFragmentUrl != null) {
            ctx.putProperty(APP_FRAGMENT_PROPERTY, appFragmentUrl.toExternalForm());
        }

        // ── 3. Run the master logback.xml as ordinary XML ─────────────────────
        // doConfigure does not reset, so the supplier + property set above survive.
        URL masterUrl = appCL.getResource("logback.xml");
        if (masterUrl == null) {
            ctx.getStatusManager().add(new WarnStatus(
                    "logback.xml not visible to appCL; logging is unconfigured", ctx));
            return;
        }
        try {
            JoranConfigurator jc = new JoranConfigurator();
            jc.setContext(ctx);
            jc.doConfigure(masterUrl);
        } catch (JoranException e) {
            ctx.getStatusManager().add(new WarnStatus(
                    "Failed to configure from " + masterUrl, ctx, e));
            return;
        }

        // ── 4. The varieties with no supplier registry: build them by hand ────
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        attachRangeFilter(ctx, root);   // custom filter on the XML-defined CONSOLE
        addJsonAppender(ctx, root);     // custom layout (+ nested formatter) + custom filter
    }

    /**
     * Attaches the runtime-only {@link ThresholdRangeFilter} to the CONSOLE
     * appender defined in logback.xml. A filter is not a converter, so the
     * supplier hook does not apply and {@code <filter class="...">} could not
     * resolve this class across the CL boundary.
     */
    private static void attachRangeFilter(LoggerContext ctx, Logger root) {
        Appender<ILoggingEvent> console = root.getAppender("CONSOLE");
        if (console == null) {
            ctx.getStatusManager().add(new WarnStatus(
                    "CONSOLE appender not found; range filter not attached", ctx));
            return;
        }
        ThresholdRangeFilter filter = new ThresholdRangeFilter();
        filter.setContext(ctx);
        filter.setMinLevel("TRACE");
        filter.setMaxLevel("ERROR");
        filter.start();
        console.addFilter(filter);
    }

    /**
     * Builds a second appender that uses the custom {@link JsonLayout} — the
     * layout variety, complete with its nested {@code jsonFormatter} component
     * and {@code logOrigin} property — and guards it with the custom
     * {@link KafkaLogFilter}. Mirrors the real config's:
     *
     * <pre>{@code
     * <layout class="kalix.runtime.LogbackJsonLayout">
     *   <jsonFormatter class="...JacksonJsonFormatter"/>
     *   <logOrigin>sdk</logOrigin>
     * </layout>
     * <filter class="kalix.runtime.eventing.kafka.KafkaLogFilter"/>
     * }</pre>
     *
     * Neither the layout nor the filter could be named in XML across the CL
     * boundary, so the whole appender is assembled programmatically.
     */
    private static void addJsonAppender(LoggerContext ctx, Logger root) {
        // Custom layout + its nested component + scalar property (all set by hand).
        JsonLayout layout = new JsonLayout();
        layout.setContext(ctx);
        layout.setJsonFormatter(new DefaultJsonFormatter());
        layout.setLogOrigin("sdk");
        layout.start();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(ctx);
        encoder.setLayout(layout);
        encoder.start();

        ConsoleAppender<ILoggingEvent> jsonAppender = new ConsoleAppender<>();
        jsonAppender.setContext(ctx);
        jsonAppender.setName("JSON");
        jsonAppender.setEncoder(encoder);

        KafkaLogFilter kafkaFilter = new KafkaLogFilter();
        kafkaFilter.setContext(ctx);
        kafkaFilter.setDeniedLoggerPrefix("org.apache.kafka");
        kafkaFilter.start();
        jsonAppender.addFilter(kafkaFilter);

        jsonAppender.start();
        root.addAppender(jsonAppender);
    }
}
