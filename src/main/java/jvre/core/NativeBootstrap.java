package jvre.core;

import org.lwjgl.system.Configuration;

/**
 * One-time process setup for the LWJGL natives jvre rides on.
 *
 * <p>Today it does one thing: raise LWJGL's per-thread {@code MemoryStack} size so
 * jvre boots on extension-rich drivers. LWJGL's own {@code VkInstance} constructor
 * eagerly enumerates the GPU's DEVICE EXTENSIONS onto the shared MemoryStack to build
 * its capability cache. Each {@code VkExtensionProperties} is ~260 bytes (a 256-char
 * name + version), and a modern driver reports HUNDREDS of them -- enough to overflow
 * the default 64 KB stack with {@code OutOfMemoryError: Out of stack space}, deep
 * inside LWJGL before jvre runs a line of its own. A real user hit exactly this.
 *
 * <p>jvre can't change LWJGL's internal allocation, so the only lever is the stack
 * SIZE -- and {@link Configuration#STACK_SIZE} only takes effect BEFORE a thread's
 * stack is first created (it's lazy, sized once on first use). So this is invoked
 * from the STATIC INITIALIZER of jvre's entry-point classes ({@link Window},
 * {@link Instance}) -- class initialization runs before the constructor body, hence
 * before either class first touches a stack. That removes the old burden of making
 * callers set {@code Configuration.STACK_SIZE} by hand.
 *
 * <p>Idempotent and polite: it never SHRINKS a larger size a caller already chose.
 */
final class NativeBootstrap {

    /** A generous per-thread MemoryStack, in KB (LWJGL's default is 64). Ample for
     *  hundreds of device extensions plus jvre's own transient stack allocations. */
    private static final int STACK_SIZE_KB = 512;

    private static boolean done;

    private NativeBootstrap() {}

    /**
     * Raise {@link Configuration#STACK_SIZE} to {@value #STACK_SIZE_KB} KB unless it
     * is already at least that. Only the FIRST call -- before any stack use on the
     * thread -- actually changes anything; later calls (and a caller who set a larger
     * value) are no-ops. Synchronized so concurrent first-touch is safe.
     */
    static synchronized void ensureStackCapacity() {
        if (done) {
            return;
        }
        done = true;
        Integer current = Configuration.STACK_SIZE.get();
        if (current == null || current < STACK_SIZE_KB) {
            Configuration.STACK_SIZE.set(STACK_SIZE_KB);
        }
    }
}
