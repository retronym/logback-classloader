package com.example.boot;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Boot entry point for the {@code -Djava.system.class.loader} solution.
 *
 * <p>By the time {@code main()} is called, the JVM has already replaced the
 * system classloader with {@link AkkaSystemClassLoader}.  This class runs
 * inside that CL.
 *
 * <p>Two run modes:
 * <ul>
 *   <li><b>True embedded</b> ({@code run-system-cl.sh}) — SDK + App jars are
 *       passed via {@code -cp} so {@code AkkaSystemClassLoader} picks them up
 *       immediately from {@code java.class.path}.  {@code main()} only needs to
 *       know the runtime classpath.</li>
 *   <li><b>Simulated embedded</b> ({@code mvn exec:exec@system-cl}) — only
 *       {@code boot/target/classes} is on the initial {@code -cp}.  {@code main()}
 *       reads sdk-cp.txt and app-cp.txt and calls {@code addJar()} to populate
 *       the system CL dynamically before logback sees anything.</li>
 * </ul>
 *
 * <p>After setup, logback initialises when {@code RuntimeMain}'s static
 * {@code Logger} field is first touched.  At that point:
 * <pre>
 *   Loader.getClassLoaderOfObject(configurator)
 *     = DefaultJoranConfigurator.class.getClassLoader()
 *     = AkkaSystemClassLoader           ← our CL, with downward search
 *
 *   sysCL.getResource("logback.xml")   → downward → runtimeCL → runtime.jar ✓
 *   sysCL.loadClass("CorrelationIdLayout") → downward → runtimeCL ✓
 *   sysCL.getResource("logback-app.xml")   → own jars → app.jar ✓
 * </pre>
 *
 * <p>No agent, no {@link LogbackBridge}, no sidecar.  The system CL itself is
 * the bridge.
 */
public class SystemCLBootMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: SystemCLBootMain <sdk-cp.txt> <runtime-cp.txt> <app-cp.txt>");
            System.exit(1);
        }

        ClassLoader sysCL = ClassLoader.getSystemClassLoader();
        if (!(sysCL instanceof AkkaSystemClassLoader)) {
            System.err.println("System CL is not AkkaSystemClassLoader: " + sysCL.getClass());
            System.err.println("Did you forget -Djava.system.class.loader=com.example.boot.AkkaSystemClassLoader ?");
            System.exit(1);
        }
        AkkaSystemClassLoader akkaSystemCL = (AkkaSystemClassLoader) sysCL;

        URL[] sdkUrls     = readClasspathFile(args[0]);
        URL[] runtimeUrls = readClasspathFile(args[1]);
        URL[] appUrls     = readClasspathFile(args[2]);

        // ── Populate system CL with SDK + App ─────────────────────────────────
        // In true embedded mode (run-system-cl.sh) these are already on -cp and
        // hence already in java.class.path — addJar() will add duplicate URLs,
        // which URLClassLoader silently deduplicates at search time.
        // In simulated mode (mvn exec:exec@system-cl) -cp has only boot classes,
        // so this is the only way the system CL acquires sdk + app.
        System.out.println("=== Adding SDK + App to system CL ===");
        for (URL u : sdkUrls) { akkaSystemCL.addJar(u); System.out.println("[+] " + u); }
        for (URL u : appUrls) { akkaSystemCL.addJar(u); System.out.println("[+] " + u); }

        // ── Create Runtime CL — isolated, parent = system CL ──────────────────
        URLClassLoader runtimeCL = new URLClassLoader(
                "runtime-classloader", runtimeUrls, akkaSystemCL);
        System.out.println("\n[CL] system  = " + akkaSystemCL);
        System.out.println("[CL] runtime = " + runtimeCL);

        // ── Register Runtime CL as downward search target ─────────────────────
        // From this point on, any lookup the system CL cannot satisfy in its own
        // jars (sdk + logback + app) is forwarded to runtimeCL.
        akkaSystemCL.addChild(runtimeCL);

        // Resource visibility proof
        System.out.println("\n=== Resource visibility via system CL ===");
        probeResource(akkaSystemCL, "logback.xml");
        probeResource(akkaSystemCL, "logback-app.xml");
        probeResource(akkaSystemCL, "com/example/runtime/layout/CorrelationIdLayout.class");
        System.out.println();

        // ── Load RuntimeMain — this triggers logback init ──────────────────────
        Thread.currentThread().setContextClassLoader(runtimeCL);

        Class<?> runtimeMainClass = runtimeCL.loadClass("com.example.runtime.RuntimeMain");
        System.out.println("[Boot] RuntimeMain loaded by: " + runtimeMainClass.getClassLoader());
        System.out.println();

        Method run = runtimeMainClass.getMethod("run", ClassLoader.class);
        run.invoke(null, runtimeCL);
    }

    private static URL[] readClasspathFile(String filePath) throws Exception {
        String cp = Files.readString(Path.of(filePath)).trim();
        if (cp.isEmpty()) return new URL[0];
        String[] entries = cp.split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) urls[i] = toUrl(entries[i].trim());
        return urls;
    }

    private static URL toUrl(String path) throws MalformedURLException {
        return new File(path).toURI().toURL();
    }

    private static void probeResource(ClassLoader cl, String resource) {
        URL url = cl.getResource(resource);
        System.out.printf("[RES] %-60s → %s%n",
                resource, url != null ? url : "NOT FOUND");
    }
}
