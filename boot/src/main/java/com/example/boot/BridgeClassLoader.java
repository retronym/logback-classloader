package com.example.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A ClassLoader that is <em>not</em> part of the parent-delegation hierarchy
 * but acts as a bridge: it is returned by the agent-patched
 * {@code Loader.getClassLoaderOfObject()} so that logback uses it for every
 * class and resource lookup that ordinarily would return the CL of
 * {@code DefaultJoranConfigurator} or similar logback internals.
 *
 * <p>The bridge holds no jars of its own.  Its parent is the "shared" CL
 * (system CL in true embedded mode, or a URLClassLoader that covers SDK+App in
 * the simulated embedded mode run here).  When the shared parent cannot satisfy
 * a request the bridge performs a <em>downward</em> search across its registered
 * child CLs — typically the Runtime CL, which holds {@code runtime.jar}.
 *
 * <p>Anti-loop: a {@link ThreadLocal} guards against the re-entrant cycle that
 * would otherwise occur when a child CL's normal upward delegation reaches the
 * shared parent (via SDK CL → shared CL) and then back into the bridge's
 * downward search.
 *
 * <pre>
 *   getClassLoaderOfObject()
 *     └── returns BridgeClassLoader                      ← agent intercept
 *
 *   BridgeClassLoader.loadClass(X)
 *     1. parent (shared CL)          — SDK + App visible here
 *     2. own jars                    — empty
 *     3. downward → runtimeCL        — runtime.jar visible here
 *          runtimeCL.loadClass(X)
 *            delegates to shared CL  — not found (or found, no loop)
 *            own (runtime.jar)       — found ✓
 *
 *   BridgeClassLoader.getResource(X)
 *     1. parent (shared CL)          — logback-app.xml in app.jar (embedded) ✓
 *     2. downward → runtimeCL        — logback.xml in runtime.jar ✓
 * </pre>
 */
public class BridgeClassLoader extends URLClassLoader {

    private static final ThreadLocal<Boolean> IN_DOWNWARD_SEARCH =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final List<ClassLoader> children =
            Collections.synchronizedList(new ArrayList<>());

    /** @param parent the "shared" CL (SDK + App visible here) */
    public BridgeClassLoader(String name, URL[] ownJars, ClassLoader parent) {
        super(name, ownJars, parent);
    }

    public void addChild(ClassLoader child) {
        children.add(child);
    }

    // ── Class loading ────────────────────────────────────────────────────────

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }

            // 1. Shared parent (SDK + App in embedded mode)
            try { c = getParent().loadClass(name); }
            catch (ClassNotFoundException ignored) {}

            // 2. Own jars (empty — bridge holds no jars of its own)
            if (c == null) {
                try { c = findClass(name); }
                catch (ClassNotFoundException ignored) {}
            }

            // 3. Downward — guarded against re-entry
            if (c == null && !IN_DOWNWARD_SEARCH.get()) {
                IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
                try {
                    for (ClassLoader child : children) {
                        try { c = child.loadClass(name); break; }
                        catch (ClassNotFoundException ignored) {}
                    }
                } finally {
                    IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
                }
            }

            if (c == null) throw new ClassNotFoundException(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    // ── Resource loading ─────────────────────────────────────────────────────

    @Override
    public URL getResource(String name) {
        URL url = super.getResource(name); // parent + own (empty)
        if (url == null && !IN_DOWNWARD_SEARCH.get()) {
            IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
            try {
                for (ClassLoader child : children) {
                    url = child.getResource(name);
                    if (url != null) break;
                }
            } finally {
                IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
            }
        }
        return url;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Enumeration<URL> own = super.getResources(name);
        if (IN_DOWNWARD_SEARCH.get()) return own;

        List<URL> all = new ArrayList<>();
        while (own.hasMoreElements()) all.add(own.nextElement());

        IN_DOWNWARD_SEARCH.set(Boolean.TRUE);
        try {
            for (ClassLoader child : children) {
                Enumeration<URL> more = child.getResources(name);
                while (more.hasMoreElements()) {
                    URL u = more.nextElement();
                    if (!all.contains(u)) all.add(u);
                }
            }
        } finally {
            IN_DOWNWARD_SEARCH.set(Boolean.FALSE);
        }
        return Collections.enumeration(all);
    }
}
