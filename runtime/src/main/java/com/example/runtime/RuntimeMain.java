package com.example.runtime;

import com.example.sdk.ServiceEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Entry point discovered and invoked by BootMain via reflection.
 *
 * Loaded by the Runtime ClassLoader.  The static logger field triggers
 * logback's first initialization — by the time this class is loaded,
 * BootMain must have already set the TCCL to the App ClassLoader so that:
 *   1. logback.xml is found (in runtime.jar, reachable via App CL parent chain)
 *   2. CorrelationIdLayout / ThresholdRangeFilter are instantiated (runtime.jar)
 *   3. logback-app.xml <include> is resolved (in app.jar, found by App CL)
 */
public class RuntimeMain {

    // Static init fires on class load — TCCL must be App CL at that point.
    private static final Logger log = LoggerFactory.getLogger(RuntimeMain.class);

    public static void run(ClassLoader appCL) {
        // Solution I: configure logback programmatically from the Runtime layer,
        // before the first log call. The custom conversion word is registered as
        // a Supplier (constructor reference), so logback never loads a runtime
        // class by name across the classloader boundary.
        SupplierBasedLogbackConfig.configure(appCL);

        log.info("RuntimeMain starting");
        log.info("  RuntimeMain loaded by : {}", RuntimeMain.class.getClassLoader());
        log.info("  ILoggerFactory        : {}", LoggerFactory.getILoggerFactory());
        log.info("  TCCL                  : {}", Thread.currentThread().getContextClassLoader());

        ServiceLoader<ServiceEndpoint> loader =
                ServiceLoader.load(ServiceEndpoint.class, appCL);

        int found = 0;
        for (ServiceEndpoint ep : loader) {
            log.info("Discovered endpoint '{}' (loaded by: {})",
                    ep.name(), ep.getClass().getClassLoader());
            ep.handle("ping");
            found++;
        }
        log.info("Total endpoints: {}", found);
    }
}
