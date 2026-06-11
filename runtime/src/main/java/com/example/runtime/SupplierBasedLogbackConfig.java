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
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.pattern.DynamicConverter;
import ch.qos.logback.core.status.WarnStatus;

import com.example.runtime.filter.ThresholdRangeFilter;
import com.example.runtime.layout.CorrelationIdConverter;

import org.slf4j.LoggerFactory;

/**
 * Solution I — register the supplier programmatically, then drive the rest of
 * the configuration from XML.
 *
 * <p>The other solutions in this repo attack the root cause from the pull side:
 * logback (in a parent CL) tries to {@code Class.forName} a class named in
 * {@code logback.xml} that only exists in a child CL, so they bend the
 * classloader graph (custom Layer-0 CL, agent rewrite, system-CL replacement,
 * self-attach) until logback's CL can see downward.</p>
 *
 * <p>Here the Runtime layer does two small things up front, then hands control
 * back to ordinary Joran/XML config:</p>
 * <ol>
 *   <li><b>Registers the custom conversion word as a supplier.</b> The pattern in
 *       {@code logback.xml} uses {@code %correlationId}; we put
 *       {@code CorrelationIdConverter::new} into the context's
 *       {@link CoreConstants#PATTERN_RULE_REGISTRY_FOR_SUPPLIERS}. logback's
 *       {@code Compiler} calls {@code supplier.get()} — no {@code Loader.loadClass},
 *       no TCCL, no CL surgery.</li>
 *   <li><b>Publishes the app fragment URL.</b> We resolve {@code logback-app.xml}
 *       via {@code appCL} and expose it as the {@code ${appFragmentUrl}} property,
 *       so the master's {@code <include url="...">} opens it with {@code new URL(...)}
 *       instead of {@code Loader.getResourceBySelfClassLoader}.</li>
 * </ol>
 *
 * <p>The master {@code logback.xml} (resolved via {@code appCL}) is then run as
 * plain XML. The only thing that cannot live in that XML is the custom
 * {@link ThresholdRangeFilter}: a filter is not a converter and logback has no
 * supplier registry for filters, so a runtime-only filter class still can't be
 * named in XML across the CL boundary. It is attached programmatically afterwards.</p>
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

        // ── 1. The supplier hook ──────────────────────────────────────────────
        // Fill the same registry slot that <conversionRule> populates, but with a
        // real constructor reference instead of a class-name string. Built fully,
        // published once (safe publication via the context's ConcurrentHashMap),
        // never mutated afterwards. logback.xml's %correlationId resolves here.
        Map<String, Supplier<DynamicConverter>> rules = new HashMap<>();
        rules.put("correlationId", CorrelationIdConverter::new);
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

        // ── 4. Attach the custom filter (no XML path exists for it) ───────────
        attachRangeFilter(ctx);
    }

    /**
     * Attaches the runtime-only {@link ThresholdRangeFilter} to the CONSOLE
     * appender defined in logback.xml. This is the one piece that cannot be
     * expressed as {@code <filter class="...">} in XML: a filter is not a
     * converter, so the supplier hook does not apply, and logback would resolve
     * the class via its own (SDK) classloader, which cannot see runtime.jar.
     */
    private static void attachRangeFilter(LoggerContext ctx) {
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> console = root.getAppender("CONSOLE");
        if (console == null) {
            ctx.getStatusManager().add(new WarnStatus(
                    "CONSOLE appender not found; filter not attached", ctx));
            return;
        }
        ThresholdRangeFilter filter = new ThresholdRangeFilter();
        filter.setContext(ctx);
        filter.setMinLevel("TRACE");
        filter.setMaxLevel("ERROR");
        filter.start();
        console.addFilter(filter);
    }
}
