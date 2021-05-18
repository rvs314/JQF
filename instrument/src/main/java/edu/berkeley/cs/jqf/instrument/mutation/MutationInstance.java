package edu.berkeley.cs.jqf.instrument.mutation;

import org.objectweb.asm.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicLong;

/** mostly exported from InstrumentingClassLoader with additions to FindClass **/
public class MutationInstance extends URLClassLoader {

    /** which mutator to use */
    private final Mutator mutator;

    /** numbered instance of the opportunity for mutation this classloader uses */
    private final long instance;

    /** name of the class to mutate */
    private final String mutateName;

    /** whether this mutation has been killed already */
    private boolean dead;

    private final String className = this.getClass().getName();
    private static final int MAX_ITERATIONS = 100000;
    private int jumps;

    //TODO potential for more information:
    //  line number
    //  who's seen it
    //  whether this mutation is likely to be killed by a particular input

    public MutationInstance(URL[] paths, ClassLoader parent, Mutator m, long i, String n) throws MalformedURLException {
        super(paths, parent);
        mutator = m;
        instance = i;
        mutateName = n;
        dead = false;
        jumps = 0;
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes;

        String internalName = name.replace('.', '/');
        String path = internalName.concat(".class");
        try (InputStream in = super.getResourceAsStream(path)) {
            if (in == null) {
                throw new ClassNotFoundException("Cannot find class " + name);
            }
            BufferedInputStream buf = new BufferedInputStream(in);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = buf.read()) != -1) {
                baos.write(b);
            }
            bytes = baos.toByteArray();
        } catch (IOException e) {
            throw new ClassNotFoundException("I/O exception while loading class.", e);
        }

        if(name.equals(mutateName)) {
            AtomicLong found = new AtomicLong(0);
            ClassWriter cw = new ClassWriter(0);
            ClassReader cr = new ClassReader(bytes);
            cr.accept(new ClassVisitor(Mutator.cvArg, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String signature,
                                                 String superName, String[] interfaces) {
                    return new MethodVisitor(Mutator.cvArg, cv.visitMethod(access, name,
                            signature, superName, interfaces)) {
                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "timeoutCheck", "()V", false);
                            if (opcode == mutator.toReplace() && found.get() == instance) {
                                for (InstructionCall ic : mutator.replaceWith(signature, false)) {
                                    ic.call(mv, label);
                                }
                                found.getAndIncrement();
                            } else if (opcode == mutator.toReplace()) {
                                super.visitJumpInsn(opcode, label);
                                found.getAndIncrement();
                            } else {
                                super.visitJumpInsn(opcode, label);
                            }
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (Opcodes.LDC == mutator.toReplace() && found.get() == instance) {
                                for (InstructionCall ic : mutator.replaceWith(signature, false)) {
                                    ic.call(mv, null);
                                }
                                found.getAndIncrement();
                            } else if (Opcodes.LDC == mutator.toReplace()) {
                                super.visitLdcInsn(value);
                                found.getAndIncrement();
                            } else {
                                super.visitLdcInsn(value);
                            }
                        }
                        @Override
                        public void visitIincInsn(int var, int increment) {
                            if (Opcodes.IINC == mutator.toReplace() && found.get() == instance) {
                                super.visitIincInsn(var, -increment);
                                found.getAndIncrement();
                            } else if (Opcodes.IINC == mutator.toReplace()) {
                                super.visitIincInsn(var, increment);
                                found.getAndIncrement();
                            } else {
                                super.visitIincInsn(var, increment);
                            }
                        }
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            //TODO make this one guard
                            if (mutator.isOpportunity(opcode, descriptor) && found.get() == instance) {
                                for (InstructionCall ic : mutator.replaceWith(descriptor, opcode == Opcodes.INVOKESTATIC/*owner.equals(internalName)*/)) {
                                    ic.call(mv, null);
                                }
                                found.getAndIncrement();
                            } else if (mutator.isOpportunity(opcode, descriptor)) {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                found.getAndIncrement();
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
                        }
                        @Override
                        public void visitInsn(int opcode) {
                            if (mutator.isOpportunity(opcode, signature) && found.get() == instance) {
                                for (InstructionCall ic : mutator.replaceWith(signature, false)) {
                                    ic.call(mv, null);
                                }
                                found.getAndIncrement();
                            } else if (mutator.isOpportunity(opcode, signature)) {
                                super.visitInsn(opcode);
                                found.getAndIncrement();
                            } else {
                                super.visitInsn(opcode);
                            }
                        }
                    };
                }
            }, 0);
            bytes = cw.toByteArray();
        }

        return defineClass(name, bytes, 0, bytes.length);
    }

    public void kill() {
        dead = true;
    }

    public boolean isDead() {
        return dead;
    }

    /**
     * Insert calls to this as instrumentation in the program
     * (see line 81)
     */
    public void timeoutCheck() throws MutationTimeoutException {
        if(++jumps > MAX_ITERATIONS) {
            throw new MutationTimeoutException();
        }
    }

    public void resetTimeout() {
        jumps = 0;
    }

    @Override
    public String toString() {
        String toReturn = "MutationInstance " + instance + " of " + mutator + " in " + mutateName + " (";
        if(dead) {
            return toReturn + "dead)";
        } else {
            return toReturn + "alive)";
        }
    }
}
