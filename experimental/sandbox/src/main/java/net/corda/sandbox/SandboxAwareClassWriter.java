package net.corda.sandbox;

import static net.corda.sandbox.Utils.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 *
 * @author ben
 */
public final class SandboxAwareClassWriter extends ClassWriter {

    private final ClassLoader loader;

    public SandboxAwareClassWriter(final ClassLoader save, final ClassReader classReader, final int flags) {
        super(classReader, flags);
        loader = save;
    }

    /**
     * Returns the common super type of the two given types. The default
     * implementation of this method <i>loads</i> the two given classes and uses
     * the java.lang.Class methods to find the common super class. It can be
     * overridden to compute this common super type in other ways, in particular
     * without actually loading any class, or to take into account the class
     * that is currently being generated by this ClassWriter, which can of
     * course not be loaded since it is under construction.
     * 
     * @param type1
     *            the internal name of a class.
     * @param type2
     *            the internal name of another class.
     * @return the internal name of the common super class of the two given
     *         classes.
     */
    @Override
    public String getCommonSuperClass(final String type1, final String type2) {
        if (OBJECT.equals(type1) || OBJECT.equals(type2) 
                || OBJECT.equals(unsandboxNameIfNeedBe(type1)) || OBJECT.equals(unsandboxNameIfNeedBe(type2))) {
            return OBJECT;
        }
//        System.out.println(type1 + " ; " + type2);
        String out = super.getCommonSuperClass(unsandboxNameIfNeedBe(type1), unsandboxNameIfNeedBe(type2));
//        try {
//           out = getCommonSuperClassBorrowed(type1, type2);        
//        } catch (final ClassNotFoundException cnfe) {
//            throw new RuntimeException(cnfe);
//        }
        if (SANDBOX_PATTERN_INTERNAL.asPredicate().test(type1) || SANDBOX_PATTERN_INTERNAL.asPredicate().test(type2)) {
            return SANDBOX_PREFIX_INTERNAL + out;
        }
        return out;
    }

    public String getCommonSuperClassBorrowed(final String type1, final String type2) throws ClassNotFoundException {
        Class<?> c, d;
        try {
            c = Class.forName(type1.replace('/', '.'), false, loader);
            d = Class.forName(type2.replace('/', '.'), false, loader);
        } catch (Exception e) {
            
            c = Class.forName(unsandboxNameIfNeedBe(type1).replace('/', '.'), false, loader);
            d = Class.forName(unsandboxNameIfNeedBe(type2).replace('/', '.'), false, loader);

//            throw new RuntimeException(e.toString());
        }
        if (c.isAssignableFrom(d)) {
            return type1;
        }
        if (d.isAssignableFrom(c)) {
            return type2;
        }
        if (c.isInterface() || d.isInterface()) {
            return "java/lang/Object";
        } else {
            do {
                c = c.getSuperclass();
            } while (!c.isAssignableFrom(d));
            return c.getName().replace('.', '/');
        }
    }

}
