package org.codehaus.janino;

import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Test-only signature fixture; not the upstream implementation. */
public class JavaSourceClassLoader extends ClassLoader {
    private boolean debugSource = true;
    private boolean debugLines = true;
    private boolean debugVars = true;
    protected Object optionalProtectionDomainFactory;
    public int originalCalls;

    protected Map<String, byte[]> generateBytecodes(String name) throws ClassNotFoundException {
        originalCalls++;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
                "java/lang/Object", null);
        writer.visitEnd();
        return Map.of(name, writer.toByteArray());
    }

    public Map<String, byte[]> compile(String name) throws ClassNotFoundException {
        return generateBytecodes(name);
    }

    public void setDebuggingInfo(boolean source, boolean lines, boolean vars) {
        debugSource = source;
        debugLines = lines;
        debugVars = vars;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }
}
