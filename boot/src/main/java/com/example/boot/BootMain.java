package com.example.boot;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads three classpath files produced by Maven and constructs a layered
 * classloader hierarchy that mirrors the Akka Runtime isolation model:
 *
 * <pre>
 *   PlatformClassLoader  (JVM bootstrap + java.*)
 *        └── SDK ClassLoader      sdk.jar + logback-classic + slf4j-api + ...
 *             └── Runtime ClassLoader   runtime.jar
 *                  └── App ClassLoader       app.jar
 * </pre>
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  LOGBACK CLASSLOADER CHALLENGES (logback 1.5.x / SLF4J 2.x)           │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │                                                                         │
 * │  CHALLENGE 1 — logback.xml discovery                                   │
 * │    DefaultJoranConfigurator.performMultiStepConfigurationFileSearch     │
 * │    calls Loader.getClassLoaderOfObject(this) which returns              │
 * │    DefaultJoranConfigurator.class.getClassLoader() = SDK CL.           │
 * │    logback.xml lives in runtime.jar (Runtime CL).  SDK CL cannot see   │
 * │    it → DefaultJoranConfigurator returns INVOKE_NEXT_IF_ANY →          │
 * │    BasicConfigurator runs (default DEBUG root, TTLLLayout).            │
 * │    There is NO TCCL fallback in performMultiStepConfigurationFileSearch.│
 * │                                                                         │
 * │    FIX: set system property "logback.configurationFile" to the URL     │
 * │    resolved via appCL BEFORE logback is first initialised.             │
 * │                                                                         │
 * │  CHALLENGE 2 — Layout / Filter class instantiation                     │
 * │    Joran's Loader.loadClass(className, context) first tries            │
 * │    context.getClass().getClassLoader() (= SDK CL) → ClassNotFound,    │
 * │    then falls back to getTCL() (= TCCL = appCL) →                     │
 * │    delegates to runtimeCL → CorrelationIdLayout found ✓               │
 * │    TCCL must be appCL at logback config time; see setContextClassLoader │
 * │    call below.                                                          │
 * │                                                                         │
 * │  CHALLENGE 3 — <include resource="logback-app.xml">                   │
 * │    Joran resolves included resources via Loader.getResource(name, cl)  │
 * │    where cl = DefaultJoranConfigurator.class.getClassLoader() (SDK CL).│
 * │    logback-app.xml lives in app.jar → NOT found from SDK CL.          │
 * │    FIX: implement a custom Configurator (SDK-visible via SPI) that     │
 * │    drives JoranConfigurator with appCL, OR override the classloader    │
 * │    used by the Joran context.  Left as an exercise to explore next.    │
 * │                                                                         │
 * │  Observable outcomes to verify fixes:                                  │
 * │    - log format shows CorrelationIdLayout   => CHALLENGE 2 solved      │
 * │    - "[DEBUG]" line from MyServiceEndpoint  => CHALLENGE 3 solved      │
 * │    - logback status messages appear (debug="true" in logback.xml)      │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
public class BootMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: BootMain <sdk-cp.txt> <runtime-cp.txt> <app-cp.txt>");
            System.exit(1);
        }

        URL[] sdkUrls     = readClasspathFile(args[0]);
        URL[] runtimeUrls = readClasspathFile(args[1]);
        URL[] appUrls     = readClasspathFile(args[2]);

        System.out.println("=== Layered ClassLoader Setup ===");
        printUrls("SDK     ", sdkUrls);
        printUrls("Runtime ", runtimeUrls);
        printUrls("App     ", appUrls);

        // --- Build the classloader hierarchy ---

        URLClassLoader sdkCL = new URLClassLoader(
                "sdk-classloader", sdkUrls, ClassLoader.getPlatformClassLoader());

        URLClassLoader runtimeCL = new URLClassLoader(
                "runtime-classloader", runtimeUrls, sdkCL);

        URLClassLoader appCL = new URLClassLoader(
                "app-classloader", appUrls, runtimeCL);

        System.out.println("\n[CL] " + sdkCL);
        System.out.println("[CL] " + runtimeCL);
        System.out.println("[CL] " + appCL);

        // Diagnostic: confirm resources are (or are not) reachable from each CL.
        System.out.println("\n=== Resource visibility ===");
        probeResource(sdkCL,     "sdk    ", "logback.xml");
        probeResource(runtimeCL, "runtime", "logback.xml");
        probeResource(appCL,     "app    ", "logback.xml");
        probeResource(appCL,     "app    ", "logback-app.xml");
        probeResource(sdkCL,     "sdk    ", "com/example/runtime/layout/CorrelationIdLayout.class");
        probeResource(appCL,     "app    ", "com/example/runtime/layout/CorrelationIdLayout.class");

        System.out.println();

        // ── Solution I — no classloader tricks ────────────────────────────────
        // No logback.configurationFile, no TCCL juggling. RuntimeMain configures
        // logback programmatically from the Runtime layer (which can see its own
        // classes), registering the custom conversion word as a Supplier rather
        // than letting logback resolve it by name across the CL boundary. All
        // three original problems (xml discovery, class instantiation, <include>)
        // are sidestepped because there is no Joran resource lookup at all.

        // ── Load and invoke RuntimeMain via reflection ─────────────────────────
        // RuntimeMain.run() calls SupplierBasedLogbackConfig.configure() first.
        System.out.println();
        Class<?> runtimeMainClass = runtimeCL.loadClass("com.example.runtime.RuntimeMain");
        System.out.println("[Boot] RuntimeMain loaded by: " + runtimeMainClass.getClassLoader());
        System.out.println();

        Method run = runtimeMainClass.getMethod("run", ClassLoader.class);
        run.invoke(null, appCL);
    }

    // ---------------------------------------------------------------------------

    private static URL[] readClasspathFile(String filePath) throws Exception {
        String cp = Files.readString(Path.of(filePath)).trim();
        if (cp.isEmpty()) return new URL[0];
        String[] entries = cp.split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = toUrl(entries[i].trim());
        }
        return urls;
    }

    private static URL toUrl(String path) throws MalformedURLException {
        return new File(path).toURI().toURL();
    }

    private static void printUrls(String label, URL[] urls) {
        System.out.printf("[CP] %s:", label);
        for (URL u : urls) System.out.printf("%n         %s", u);
        System.out.println();
    }

    private static void probeResource(URLClassLoader cl, String label, String resource) {
        URL found      = cl.findResource(resource);   // own jars only
        URL delegated  = cl.getResource(resource);    // full parent chain
        System.out.printf("[RES] %-8s own=%-5s delegated=%-5s  %s%n",
                label, found != null ? "YES" : "NO", delegated != null ? "YES" : "NO", resource);
    }
}
