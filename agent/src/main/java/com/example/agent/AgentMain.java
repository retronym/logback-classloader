package com.example.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Java agent entry point.
 *
 * <p>Two things happen in {@code premain}:
 * <ol>
 *   <li>The agent JAR is appended to the <em>bootstrap</em> ClassLoader search
 *       path.  This makes {@link LogbackBridge} (in the agent JAR) visible to
 *       every ClassLoader in the JVM, including the one that loads
 *       {@code ch.qos.logback.core.util.Loader}.  Without this step, the
 *       instrumented bytecode — which calls
 *       {@code com.example.agent.LogbackBridge.get()} — would throw
 *       {@code NoClassDefFoundError} when logback's {@code Loader} class is
 *       first used.</li>
 *   <li>A {@link LoaderTransformer} is registered.  It rewrites
 *       {@code Loader.getClassLoaderOfObject(Object)} the first time that class
 *       is loaded (or immediately via retransform if it is already loaded).</li>
 * </ol>
 *
 * <p>Launch with:
 * <pre>
 *   java -javaagent:/path/to/agent.jar  ...
 * </pre>
 */
public class AgentMain {

    public static void premain(String agentArgs, Instrumentation inst) throws Exception {
        System.out.println("[agent] Logback classloader agent starting");

        // 1. Find and register the agent jar on the bootstrap CL.
        //    LogbackBridge must be loadable from wherever Loader is loaded.
        URL agentLocation = AgentMain.class.getProtectionDomain()
                                           .getCodeSource()
                                           .getLocation();
        if (agentLocation != null) {
            File agentJar = new File(agentLocation.toURI());
            if (agentJar.isFile()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
                System.out.println("[agent] Appended to bootstrap CL: " + agentJar);
            } else {
                System.err.println("[agent] WARNING: agent location is not a JAR file: "
                        + agentJar + " — LogbackBridge may not be visible from bootstrap CL");
            }
        }

        // 2. Register the transformer.  Can-Retransform-Classes=true allows it
        //    to patch Loader even if already loaded (e.g. in agent-attach scenarios).
        inst.addTransformer(new LoaderTransformer(), true);

        // 3. Eagerly retransform if Loader is already loaded.
        //    Normal startup: the transformer fires lazily when Loader is first
        //    loaded — this handles the hot-attach / already-loaded case.
        try {
            Class<?> loaderClass = Class.forName(
                    "ch.qos.logback.core.util.Loader", false,
                    ClassLoader.getSystemClassLoader());
            System.out.println("[agent] Loader already loaded — retransforming");
            inst.retransformClasses(loaderClass);
        } catch (ClassNotFoundException ignored) {
            System.out.println("[agent] Loader not yet loaded — transformer will fire on first use");
        }
    }

    /** Support attach-after-start via -agentmain (same logic). */
    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        premain(agentArgs, inst);
    }
}
