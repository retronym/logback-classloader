package com.example.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Transforms {@code ch.qos.logback.core.util.Loader.getClassLoaderOfObject(Object)}
 * to consult {@link LogbackBridge} before falling back to the original
 * {@code obj.getClass().getClassLoader()} behaviour.
 *
 * <p>Original logic (logback 1.5.x):
 * <pre>
 *   public static ClassLoader getClassLoaderOfObject(Object o) {
 *       // null-check o …
 *       return getClassLoaderOfClass(o.getClass());   // → obj's own CL
 *   }
 * </pre>
 *
 * <p>After transformation the method effectively becomes:
 * <pre>
 *   public static ClassLoader getClassLoaderOfObject(Object o) {
 *       ClassLoader bridge = LogbackBridge.get();
 *       if (bridge != null) return bridge;            // ← NEW
 *       // null-check o …
 *       return getClassLoaderOfClass(o.getClass());   // original path unchanged
 *   }
 * </pre>
 *
 * <p>This is achieved by prepending five bytecode instructions at the start of
 * the method using ASM's visitor chain — no original instructions are removed
 * or reordered.  {@code COMPUTE_FRAMES} handles all stack-map frame updates.
 *
 * <p>The injected instructions:
 * <pre>
 *   INVOKESTATIC  com/example/agent/LogbackBridge.get ()Ljava/lang/ClassLoader;
 *   DUP
 *   IFNULL        L_null
 *   ARETURN                     ; early return with bridge
 *   L_null:
 *   POP                         ; discard null, fall through to original body
 * </pre>
 */
public class LoaderTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS  = "ch/qos/logback/core/util/Loader";
    private static final String TARGET_METHOD = "getClassLoaderOfObject";
    private static final String TARGET_DESC   = "(Ljava/lang/Object;)Ljava/lang/ClassLoader;";

    @Override
    public byte[] transform(ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain pd, byte[] bytes) {

        if (!TARGET_CLASS.equals(className)) return null;

        System.out.println("[agent] Transforming " + TARGET_CLASS);
        try {
            ClassReader cr = new ClassReader(bytes);

            // COMPUTE_FRAMES regenerates all stack-map frames from scratch.
            // We override getCommonSuperClass to avoid having to load
            // application classes inside the agent's own classloader.
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {

                    MethodVisitor mv = super.visitMethod(
                            access, name, descriptor, signature, exceptions);

                    if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(descriptor)) {
                        System.out.println("[agent]   Patching: " + name + descriptor);
                        return new PrependBridgeCheck(Opcodes.ASM9, mv);
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();

        } catch (Exception e) {
            System.err.println("[agent] Transform failed: " + e);
            e.printStackTrace();
            return null; // leave original bytes intact
        }
    }

    // ── Inner visitor ────────────────────────────────────────────────────────

    /**
     * Prepends the LogbackBridge check immediately after {@code visitCode()}.
     * All original instructions are delegated unchanged to the underlying writer.
     */
    private static final class PrependBridgeCheck extends MethodVisitor {

        PrependBridgeCheck(int api, MethodVisitor mv) {
            super(api, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();  // emit the Code attribute header first

            Label bridgeIsNull = new Label();

            // ClassLoader bridge = LogbackBridge.get();
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/example/agent/LogbackBridge", "get",
                    "()Ljava/lang/ClassLoader;", false);

            // if (bridge == null) goto bridgeIsNull;
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, bridgeIsNull);

            // return bridge;   (non-null branch)
            super.visitInsn(Opcodes.ARETURN);

            // bridgeIsNull: pop the null ref, fall through to original body
            super.visitLabel(bridgeIsNull);
            super.visitInsn(Opcodes.POP);

            // Original method body follows — visitInsn/visitVarInsn/… called by ASM reader
        }
    }
}
