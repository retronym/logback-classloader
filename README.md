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

## Solution I — Supplier hooks + XML driven by the Runtime layer

**Branch: [`solution/supplier-hooks`](../../tree/solution/supplier-hooks)** · requires **logback ≥ 1.5.13**

> No agent, no custom CL, no system-CL replacement, no TCCL juggling.
> Inverts control: instead of logback **pulling** class names out of XML across
> the CL boundary, the Runtime layer **pushes** what's needed, then lets ordinary
> Joran/XML config run.

logback 1.5.13+ ([qos-ch/logback@be0b5e0](https://github.com/qos-ch/logback/commit/be0b5e027487c351a0f006c2b695a7a3fcd4918d),
[@49f0638](https://github.com/qos-ch/logback/commit/49f0638512cb1fc51f634ef4f972d25d34a9565b))
changed pattern-converter registration from `Map<String, String>` (class names)
to `Map<String, Supplier<DynamicConverter>>`. A `Supplier` can be a **constructor
reference**, so logback's `Compiler` just calls `supplier.get()` — **no
`Class.forName`, no `Loader.loadClass`, no classloader at all**.

`SupplierBasedLogbackConfig.configure(appCL)` (in `runtime.jar`, which holds
direct references to its own classes) does two things, then hands control to
plain Joran:

```java
// 1. Register every custom conversionRule as a SUPPLIER (constructor ref, not a
//    class name). Simple and composite converters share the same registry.
Map<String, Supplier<DynamicConverter>> rules = new HashMap<>();
rules.put("correlationId", CorrelationIdConverter::new);   // simple
rules.put("rewriteMsg",    RewriteMessageConverter::new);  // simple
rules.put("rewriteLogger", RewriteLoggerConverter::new);   // composite
ctx.putObject(CoreConstants.PATTERN_RULE_REGISTRY_FOR_SUPPLIERS, rules);

// 2. Publish the app fragment's URL (resolved via appCL, not logback's CL).
ctx.putProperty("appFragmentUrl", appCL.getResource("logback-app.xml").toExternalForm());

// 3. Run the master logback.xml as ordinary XML (doConfigure does NOT reset,
//    so the supplier + property set above survive into the XML pass).
JoranConfigurator jc = new JoranConfigurator();
jc.setContext(ctx);
jc.doConfigure(appCL.getResource("logback.xml"));

// 4. Layouts and filters have NO supplier registry — build them by hand.
//    e.g. a JSON appender carrying a custom JsonLayout (+ nested jsonFormatter,
//    logOrigin) and a KafkaLogFilter; plus a ThresholdRangeFilter on CONSOLE.
```

The master `logback.xml` is then plain XML, with **no FQN class references** —
the custom conversion words resolve via the suppliers registered in step 1:

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
  <encoder>
    <pattern>[%d{HH:mm:ss.SSS}] [%correlationId] [%-5level] [%rewriteLogger(%logger)] %rewriteMsg%n</pattern>
  </encoder>
</appender>
<include url="${appFragmentUrl}" optional="true"/>   <!-- url=, not resource= -->
<root level="INFO"><appender-ref ref="CONSOLE"/></root>
```

How each original problem is dispatched **without touching the classloader graph**:

| Problem | Mechanism |
|---|---|
| **1 — `logback.xml` discovery** | The Runtime layer resolves the URL itself (`appCL.getResource`) and passes it to `doConfigure(url)`. logback never has to *find* the file. |
| **2 — Layout class instantiation** | The custom Layout is re-expressed as a `%correlationId` **converter** and registered as `CorrelationIdConverter::new`. `supplier.get()` runs the constructor directly. |
| **3 — `<include resource>` resolution** | Rewritten as `<include url="${appFragmentUrl}">`. logback opens it with `new URL(...)`, bypassing `Loader.getResourceBySelfClassLoader`. The include handler aliases the fragment's `<included>` root to `<configuration>`. |

### Exercising every variety of custom class

The real system ships three kinds of custom logback class. The supplier hook
only covers one of them; the branch demonstrates the right mechanism for each:

| Variety (real class) | Sandbox class | How it's configured |
|---|---|---|
| `conversionRule`, simple — `LogbackDevModeRewriteMessage` | `RewriteMessageConverter` (`%rewriteMsg`) | Supplier hook → used straight from the XML pattern |
| `conversionRule`, composite — `LogbackDevModeRewriteLogger` | `RewriteLoggerConverter` (`%rewriteLogger(%logger)`) | Supplier hook → also covers `Compiler`'s composite path (compiles a child converter) |
| custom `<layout>` + nested `<jsonFormatter>` + `<logOrigin>` — `LogbackJsonLayout` | `JsonLayout` + `JsonFormatter`/`DefaultJsonFormatter` | **Programmatic** — `new`, then set nested component + property, then `start()`; wired into a second (JSON) appender |
| custom `<filter>` — `KafkaLogFilter` | `KafkaLogFilter`, `ThresholdRangeFilter` | **Programmatic** — `new` + setters + `start()`, then `appender.addFilter(...)` |

**The boundary of the approach** — only **converters** have a supplier registry.
A *layout* or *filter* named as `<layout class="...">` / `<filter class="...">`
would be resolved via `instantiateByClassName(…, context)` → the `LoggerContext`'s
(SDK) CL, with no TCCL fallback — so a runtime-only layout/filter class can't be
named in XML across the CL boundary. Those varieties are therefore assembled
programmatically (with `new`) and attached to their appenders after the XML pass.

> **Thread-safety caveat.** Do **not** register via
> `PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put(...)`: that field is a plain
> `static HashMap`, and `PatternLayoutBase.getEffectiveConverterMap()` reads it
> with `putAll` under no lock — a concurrent configuration races the runtime
> mutation. Publish into the **context** registry instead
> (`PATTERN_RULE_REGISTRY_FOR_SUPPLIERS`); the context object map is a
> `ConcurrentHashMap`. Discipline: build the inner map fully, publish once, never
> mutate it again.

Run: `git checkout solution/supplier-hooks && ./run.sh`

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
| **[H]** | **Self-attach agent via Instrumentation API** — BootMain dynamically loads the agent at runtime via `VirtualMachine.attach(pid)`, without `-javaagent` on the command line.  Agent patches logback like Solution 2.  Implemented in [`solution/self-attach`](../../tree/solution/self-attach); requires `-Djdk.attach.allowAttachSelf` JVM flag to enable self-attach. | runtime | ✗ | ✓ |

---

## Comparison

| | Solution 1 | Solution 2 | Solution F | Solution H | Solution I |
|---|---|---|---|---|---|
| **Mechanism** | Custom Layer 0 parent CL | Agent bytecode rewrite + sidecar CL | Replace system CL | Dynamic agent attach + sidecar CL | Supplier hook + self-resolved XML |
| **Embedded mode** | ✗ can't wrap system CL | ✓ | ✓ | ✓ | ✓ |
| **Infrastructure** | None | ASM + agent JAR | None | ASM + agent JAR | None |
| **Logback version sensitivity** | Robust | Tied to `Loader.getClassLoaderOfObject` signature | Robust | Tied to `Loader.getClassLoaderOfObject` signature | Needs ≥ 1.5.13 supplier API |
| **CL topology change** | Yes — inserts Layer 0 | No | Yes — replaces system CL | No | No |
| **Requires config** | ✗ | JVM flag `-javaagent` | JVM flag `-Djava.system.class.loader` | JVM flag `-Djdk.attach.allowAttachSelf` | ✗ |
| **Key classes** | `LogbackClassLoader` | `LoaderTransformer`, `BridgeClassLoader` | `AkkaSystemClassLoader` | `SelfAttachBootMain`, `LogbackBridge` | `SupplierBasedLogbackConfig`, `CorrelationIdConverter` |
| **Runtime overhead** | Zero | Zero | Zero | Zero | Zero |
| **Caveat** | Needs CL control | Bytecode tooling | Owns system CL | Bytecode tooling | Layouts & filters stay programmatic |

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

# Solution I — Supplier hook + self-resolved XML (no agent, no custom CL):
git checkout solution/supplier-hooks
./run.sh
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

Once `vm.loadAgent()` returns the agent has:
1. Appended the agent JAR to the bootstrap CL (`LogbackBridge` now visible everywhere)
2. Registered `LoaderTransformer` — patches `Loader.getClassLoaderOfObject()` with the same 5-instruction prologue as Solution 2
3. **Eagerly retransformed `Loader` if it was already loaded** — `Can-Retransform-Classes: true` in the MANIFEST allows this; `agentmain` calls `inst.retransformClasses()` if `Loader` is on the classpath from a prior import

Then `SelfAttachBootMain` constructs the same sidecar CL topology as Solution 2:

```
PlatformCL
  └── SharedCL   logback + SDK + App
       └── RuntimeCL   runtime.jar

BridgeClassLoader (sidecar — returned by patched getClassLoaderOfObject)
  parent = SharedCL,  child = RuntimeCL
```

`LogbackBridge.register(bridge)` is called before `RuntimeMain` is loaded, so logback's first use of `getClassLoaderOfObject` already sees the bridge.

**Self-attach requirement**: Java by default forbids a process from attaching to
itself as a security measure.  Enable it with `-Djdk.attach.allowAttachSelf`
(standard on all Java versions).  The `run-self-attach.sh` script includes this
flag, so the solution works out of the box.

Run: `git checkout solution/self-attach && mvn install && ./run-self-attach.sh`

### Toggling logback's own diagnostic output

Set `debug="true"` in [`runtime/src/main/resources/logback.xml`](runtime/src/main/resources/logback.xml)
to see the full Joran status trace, showing exactly which resources were found
or missed and which classes failed to instantiate.
