# logback-classloader

A minimal Maven sandbox for exploring how to configure **logback in a
multi-classloader environment**, modelled after the classloader isolation used
in Akka Runtime / Akka SDK.

---

## Project structure

```
sdk/        ServiceEndpoint API + logback-classic + slf4j-api
runtime/    RuntimeMain, custom Layout/Filter, master logback.xml
            (includes logback-app.xml from the app layer)
app/        MyServiceEndpoint impl, META-INF/services, logback-app.xml
boot/       Reads generated classpath files, builds layered CLs, launches RuntimeMain
agent/      Java agent that rewrites Loader.getClassLoaderOfObject() (Solution 2 only)
```

Maven's `package` phase generates three classpath files in `boot/target/`:

| File | Contents |
|---|---|
| `sdk-cp.txt` | `sdk.jar` + logback/slf4j transitives |
| `runtime-cp.txt` | `runtime.jar` only |
| `app-cp.txt` | `app.jar` only |

---

## The problem

Three things in **logback 1.5.x / SLF4J 2.x** break when logback's own
classes are in a _parent_ CL but the configuration artefacts live in a
_child_ CL.

### Root cause — `Loader.getClassLoaderOfObject`

Every logback resource and class lookup ultimately flows through:

```java
// ch.qos.logback.core.util.Loader (logback 1.5.6)
public static ClassLoader getClassLoaderOfObject(Object o) {
    return getClassLoaderOfClass(o.getClass());
}
public static ClassLoader getClassLoaderOfClass(Class<?> clazz) {
    ClassLoader cl = clazz.getClassLoader();
    return systemClassloaderIfNull(cl);
}
```

When logback calls `getClassLoaderOfObject(this)` where `this` is a
`DefaultJoranConfigurator` instance, it gets back the CL that loaded
`DefaultJoranConfigurator.class` — which is whichever CL holds
`logback-classic.jar`.  In a layered setup that is typically the SDK CL or
the system CL, neither of which can see `runtime.jar` or `app.jar`.

> **Key finding (logback 1.5.x)** — there is **no TCCL fallback** anywhere
> in this path.  `Loader.getResource(name, cl)` is a two-liner that just calls
> `cl.getResource(name)`.  Older logback versions had a TCCL fallback in
> `loadClass`; it was removed in 1.5.x.

### Problem 1 — `logback.xml` is not found

`DefaultJoranConfigurator.performMultiStepConfigurationFileSearch` calls
`Loader.getClassLoaderOfObject(this)` → SDK CL.  `logback.xml` lives in
`runtime.jar` (Runtime CL).  SDK CL cannot see it.  `DefaultJoranConfigurator`
returns `INVOKE_NEXT_IF_ANY`; `BasicConfigurator` runs instead (default DEBUG
root, TTLLLayout format).

### Problem 2 — Layout / Filter classes are not found

`Loader.loadClass(className, context)` → `getClassLoaderOfObject(context)` →
SDK CL → `ClassNotFoundException` for `CorrelationIdLayout`.  Appender silently
broken.

### Problem 3 — `<include resource="logback-app.xml">` is not resolved

Joran resolves included resources via `Loader.getResource(name, myClassLoader)`
where `myClassLoader` comes from the same `getClassLoaderOfObject` call.
`logback-app.xml` lives in `app.jar` (App CL).  Not found from SDK CL.
`optional="true"` suppresses any error — it silently does nothing.

### Observability

All three problems are visible by running the scaffold on `main`:

```bash
mvn install && mvn exec:exec -pl boot
```

| Symptom | Root cause |
|---|---|
| Log format is `[thread] LEVEL logger -- msg` (TTLLLayout) | Problem 1: BasicConfigurator used |
| No `[HH:mm:ss.SSS] [-] [LEVEL]` CorrelationIdLayout format | Problem 2: Layout class not loaded |
| No `DEBUG` line from `MyServiceEndpoint` | Problem 3: `logback-app.xml` not included |

The `boot/` diagnostic output confirms all resources _are_ reachable via
`appCL.getResource(...)` — the issue is purely which classloader logback picks.

---

## Solution 1 — `LogbackClassLoader` (Layer 0 parent CL)

**Branch: [`solution/logback-classloader`](../../tree/solution/logback-classloader)**

> Works when you control the CL hierarchy (non-embedded / production mode).

Insert a custom `LogbackClassLoader` **between `PlatformCL` and `SDK CL`**.
It holds only `logback-classic + logback-core + slf4j-api`.  Because all
logback and Joran classes are loaded by it, `getClassLoaderOfObject(this)`
returns this CL.  When its own jars cannot satisfy a request it reaches
**down** into registered child CLs — the same direction as the class we need.

```
PlatformCL
  └── LogbackClassLoader   logback-classic + logback-core + slf4j-api
       │  ↕  downward child search (ThreadLocal anti-loop guard)
       └── SDK CL           sdk.jar
            └── Runtime CL  runtime.jar  ← Layout, Filter, logback.xml
                 └── App CL  app.jar     ← logback-app.xml
```

```java
LogbackClassLoader logbackCL = new LogbackClassLoader(logbackJars, PlatformCL);
URLClassLoader sdkCL     = new URLClassLoader(sdkJars,     logbackCL);
URLClassLoader runtimeCL = new URLClassLoader(runtimeJars, sdkCL);
URLClassLoader appCL     = new URLClassLoader(appJars,     runtimeCL);
logbackCL.addChild(appCL); // appCL's parent chain covers runtimeCL too
```

Run: `git checkout solution/logback-classloader && mvn install && mvn exec:exec -pl boot`

---

## Solution 2 — Java agent rewrites `Loader.getClassLoaderOfObject`

**Branch: [`solution/agent-rewrite`](../../tree/solution/agent-rewrite)**

> Works in **embedded (dev-mode)** too, where SDK + App are on the JVM
> system classloader and the hierarchy cannot be customised.

A Java agent intercepts `ch.qos.logback.core.util.Loader` at class load time
and **prepends five bytecode instructions** to `getClassLoaderOfObject(Object)`:

```
INVOKESTATIC  com/example/agent/LogbackBridge.get  ()Ljava/lang/ClassLoader;
DUP
IFNULL        L_null
ARETURN                   ← return bridge if registered
L_null: POP               ← fall through to original body
```

`LogbackBridge` is a `volatile ClassLoader` registry on the **bootstrap CL**
(visible everywhere).  The registered value is a `BridgeClassLoader` — a
sidecar that is **not** in the parent-delegation hierarchy but performs the
same downward child-search.

```
PlatformCL
  └── SharedCL (≈ system CL)   logback + SDK + App
       └── RuntimeCL            runtime.jar

BridgeClassLoader (sidecar — returned by patched getClassLoaderOfObject)
  parent = SharedCL,  child = RuntimeCL
```

Init-order safe: `LogbackBridge.get()` returns `null` until `register()` is
called; the instrumented method falls back to original behaviour while `null`.

Run: `git checkout solution/agent-rewrite && mvn install && mvn exec:exec@embedded -pl boot`

---

## Solution F — Replace the system CL via `-Djava.system.class.loader`

**Branch: [`solution/system-classloader`](../../tree/solution/system-classloader)**

> Cleanest embedded-mode solution: no agent, no sidecar, no registry.
> The system CL **is** the bridge.

```
java -Djava.system.class.loader=com.example.boot.AkkaSystemClassLoader ...
```

The JVM replaces `ClassLoader.getSystemClassLoader()` with our custom CL
before `main()` runs.  `SystemCLBootMain` then calls `addJar()` to load SDK +
App jars into it, and `addChild(runtimeCL)` to enable downward search.
Because logback's classes are loaded by `AkkaSystemClassLoader`,
`getClassLoaderOfObject(configurator)` returns the system CL itself — which
can reach `runtime.jar` via the downward search.

```
AppClassLoader  (built-in, holds boot classes only)
  └── AkkaSystemClassLoader  (= system CL)
       │  own jars: sdk + logback + app  (added via addJar before logback init)
       │  ↕  downward child search
       └── RuntimeCL   runtime.jar
```

**CL identity trap** — the JVM uses AppClassLoader to load
`AkkaSystemClassLoader` before installing it.  If the custom CL also scanned
`java.class.path` it would load a second copy of itself, breaking `instanceof`.
Fix: keep AppClassLoader as parent (boot classes stay there, loaded once) and
start with **zero own URLs**; jars are added dynamically via `addJar()`.

```java
// SystemCLBootMain.java (simplified)
AkkaSystemClassLoader sysCL = (AkkaSystemClassLoader) ClassLoader.getSystemClassLoader();
for (URL u : sdkUrls) sysCL.addJar(u);  // now logback loads via sysCL
for (URL u : appUrls) sysCL.addJar(u);  // logback-app.xml visible in own jars
URLClassLoader runtimeCL = new URLClassLoader(runtimeUrls, sysCL);
sysCL.addChild(runtimeCL);              // logback.xml, Layout/Filter reachable
// Load RuntimeMain — getClassLoaderOfObject returns sysCL ✓
```

Run: `git checkout solution/system-classloader && mvn install && mvn exec:exec@system-cl -pl boot`

Or for true embedded (sdk+app on the real system classpath from the start):
`./run-system-cl.sh`

---

## Other approaches (brainstormed, not yet implemented)

| # | Idea | Agent? | CL restructure? | Embedded? |
|---|---|:---:|:---:|:---:|
| **A** | **Custom `Configurator` SPI in `sdk.jar`** — register via `META-INF/services`, drive `JoranConfigurator` with the right CL.  Override `buildModelInterpretationContext()` in a subclass to inject the CL for class instantiation.  Pure logback extension point, zero infrastructure. | ✗ | ✗ | ✓ |
| **B** | **`LoggerContext` subclass loaded by `appCL`** — `getClassLoaderOfObject(context)` then returns `appCL`, which has full visibility up and down.  Elegant root-cause fix; requires a stub class in `app.jar` and a custom `ContextSelector`. | ✗ | ✗ | ✓ |
| **C** | **Classpath-shadow `Loader`** — put a replacement `ch.qos.logback.core.util.Loader` in `sdk.jar` before `logback-core.jar` in the URL list.  Same effect as Solution 2's agent but via classpath ordering, no bytecode tooling. | ✗ | ✗ | partial |
| **D** | **Move logback into Runtime CL** — restructure so logback is a runtime concern not SDK.  Solves problems 1 & 2; problem 3 still needs TCCL = `appCL` for `<include resource>`. | ✗ | ✓ | ✗ |
| **E** | **Two-phase reconfiguration** — let `BasicConfigurator` run, then `ctx.stop()` / `JoranConfigurator.doConfigure(appCL.getResource("logback.xml"))` / `ctx.start()`.  No infrastructure; brief window of default formatting at startup. | ✗ | ✗ | ✓ |
| **G** | **Bootstrap-CL Loader replacement** — `appendToBootstrapClassLoaderSearch` with a single-class jar shadowing `ch.qos.logback.core.util.Loader`.  Same result as Solution 2 but no ASM — just a compiled Java file. | premain | ✗ | ✓ |
| **[H]** | **Self-attach agent via Instrumentation API** — BootMain dynamically loads the agent at runtime via `VirtualMachine.attach(pid)`, without `-javaagent` on the command line.  Agent patches logback like Solution 2.  Implemented in [`solution/self-attach`](../../tree/solution/self-attach); note: requires JVM config (`-XX:+EnableDynamicAgentLoading`) or special modes to overcome attach security restrictions. | runtime | ✗ | ✓ |

---

## Comparison

| | Solution 1 | Solution 2 | Solution F | Solution H |
|---|---|---|---|---|
| **Mechanism** | Custom Layer 0 parent CL | Agent bytecode rewrite + sidecar CL | Replace system CL | Dynamic agent attach + sidecar CL |
| **Embedded mode** | ✗ can't wrap system CL | ✓ | ✓ | ✓ |
| **Infrastructure** | None | ASM + agent JAR | None | ASM + agent JAR |
| **Logback version sensitivity** | Robust | Tied to `Loader.getClassLoaderOfObject` signature | Robust | Tied to `Loader.getClassLoaderOfObject` signature |
| **CL topology change** | Yes — inserts Layer 0 | No | Yes — replaces system CL | No |
| **Requires config** | ✗ | JVM flag `-javaagent` | JVM flag `-Djava.system.class.loader` | JVM flag `-XX:+EnableDynamicAgentLoading` |
| **Key classes** | `LogbackClassLoader` | `LoaderTransformer`, `BridgeClassLoader` | `AkkaSystemClassLoader` | `SelfAttachBootMain`, `LogbackBridge` |
| **Runtime overhead** | Zero | Zero | Zero | Zero |

All three solutions share the same **downward child-search + `ThreadLocal`
anti-loop pattern**: `IN_DOWNWARD_SEARCH` prevents the re-entrant cycle that
occurs when a child CL's normal upward delegation circles back through the
bridge CL during the downward search.

---

## Running

```bash
# Prerequisites: Java 17+, Maven 3.8+

# See the problem (main branch):
mvn install && mvn exec:exec -pl boot

# Solution 1 — Layer 0 parent CL (non-embedded):
git checkout solution/logback-classloader
mvn install && mvn exec:exec -pl boot

# Solution 2 — Java agent (embedded mode):
git checkout solution/agent-rewrite
mvn install && mvn exec:exec@embedded -pl boot
# true system-CL simulation:
./run-embedded.sh

# Solution F — Replace system CL (embedded mode):
git checkout solution/system-classloader
mvn install && mvn exec:exec@system-cl -pl boot
# true system-CL (sdk+app on real -cp from the start):
./run-system-cl.sh

# Solution H — Self-attach agent (dynamic agent loading):
git checkout solution/self-attach
mvn install && ./run-self-attach.sh
```

---

## Solution H — Dynamic agent attach via Instrumentation API

**Branch: [`solution/self-attach`](../../tree/solution/self-attach)**

> Same as Solution 2 (agent + bytecode rewrite) but **no `-javaagent` needed**.
> Agent loads dynamically at runtime via `VirtualMachine.attach()`.

`SelfAttachBootMain` uses the Java Attach API to load the agent without a
command-line flag:

```java
String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent(agentJarPath);
```

Once attached, the agent patches `Loader.getClassLoaderOfObject()` exactly like
Solution 2, and the bridge classloader handles downward child search.

**Attach API limitation**: Self-attach to the currently running JVM requires
explicit JVM configuration (`-XX:+EnableDynamicAgentLoading` on Java 9+) or
special execution modes (debugging, jshell, etc.). Standard production JVM
configs reject self-attach as a security measure.

Run: `git checkout solution/self-attach && mvn install && ./run-self-attach.sh`

The script will complete the class hierarchy setup and log output, but the
attach itself will fail with "Can not attach to current VM" unless you enable
dynamic agent loading.  To make it work, add `-XX:+EnableDynamicAgentLoading`
to the Java command in `run-self-attach.sh`.

### Toggling logback's own diagnostic output

Set `debug="true"` in [`runtime/src/main/resources/logback.xml`](runtime/src/main/resources/logback.xml)
to see the full Joran status trace, showing exactly which resources were found
or missed and which classes failed to instantiate.
