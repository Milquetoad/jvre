package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * Reflects a compiled SPIR-V module (via SPIRV-Cross) to ENFORCE the
 * {@link ShaderEffect} v1 contract at creation -- the Ring 3 guard.
 *
 * The problem this closes: a user fragment shader can COMPILE cleanly yet still
 * break the effect pipeline, which would then detonate deep inside Vulkan (with
 * validation on) or silently render garbage (with validation off). ShaderEffect
 * is jvre's first "outside input" surface, so it owes a check at the boundary,
 * in the user's terms, before any Vulkan object exists.
 *
 * Two rules, chosen because they (a) catch the actual CRASH cases and (b) are
 * robust against the optimizer -- we reflect the SAME optimized bytes jvre hands
 * to vkCreateShaderModule, so the reflection matches exactly what will run:
 *
 *   1. NO descriptor-bound resources. The effect pipeline is built with NO
 *      descriptor set layout (v1 effects bind nothing), so any UBO / sampler /
 *      storage buffer / image the shader declares is a guaranteed mismatch.
 *      Binding decorations are part of the shader interface and survive
 *      optimization -- a reliable signal.
 *   2. The push_constant block must be <= 20 bytes. jvre's pipeline declares a
 *      20-byte fragment push range (vec2 uResolution, vec2 uMouse, float uTime)
 *      and pushes exactly that each frame. A larger block means the shader reads
 *      past what jvre fills -- a validation error, or garbage. (No block at all
 *      is fine: jvre still has its range; the shader just ignores it. A block of
 *      <= 20 bytes is a valid prefix.)
 *
 * Not enforced in v1: member names/offsets (the optimizer may strip names, and
 * the size check already covers the crash case) and "never writes outColor" (a
 * blank screen, not a crash). The same reflection is the foundation for the
 * post-v1 {@code set(name, value)} uniforms.
 */
final class ShaderReflection {

    /** jvre's builtin effect push block: vec2 + vec2 + float = 20 bytes. */
    private static final int EFFECT_PUSH_BYTES = 5 * Float.BYTES;

    /**
     * SPIRV-Cross resource categories that imply a descriptor binding -- every
     * one of them is forbidden for a v1 effect (the pipeline binds no sets).
     */
    private static final int[] BOUND_RESOURCE_TYPES = {
            SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
            SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
            SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
            SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
            SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
            SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
            SPVC_RESOURCE_TYPE_SUBPASS_INPUT,
            SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE,
    };

    private ShaderReflection() {}  // static utility; no instances

    /**
     * Throw a clear, jvre-level error if the compiled fragment shader violates
     * the effect contract. Returns quietly on success.
     */
    static void checkEffectContract(byte[] spirv, String name) {
        // SPIR-V is a stream of 32-bit words. SPIRV-Cross wants them off-heap as
        // an IntBuffer; copy the bytes into a native buffer first (kept alive
        // until parsing is done, then freed).
        ByteBuffer raw = memAlloc(spirv.length).order(ByteOrder.nativeOrder());
        raw.put(spirv).flip();
        IntBuffer words = raw.asIntBuffer();

        long context = 0;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pContext = stack.pointers(0);
            check(spvc_context_create(pContext), "create SPIRV-Cross context", 0);
            context = pContext.get(0);

            PointerBuffer pParsed = stack.pointers(0);
            check(spvc_context_parse_spirv(context, words, spirv.length / 4, pParsed),
                    "parse SPIR-V for " + name, context);

            // SPVC_BACKEND_NONE = a reflection-only compiler (no GLSL/HLSL output).
            PointerBuffer pCompiler = stack.pointers(0);
            check(spvc_context_create_compiler(context, SPVC_BACKEND_NONE, pParsed.get(0),
                            SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pCompiler),
                    "create reflection compiler for " + name, context);
            long compiler = pCompiler.get(0);

            PointerBuffer pResources = stack.pointers(0);
            check(spvc_compiler_create_shader_resources(compiler, pResources),
                    "reflect resources for " + name, context);
            long resources = pResources.get(0);

            rejectBoundResources(resources, name, context, stack);
            checkPushConstantSize(compiler, resources, name, context, stack);
        } finally {
            if (context != 0) {
                spvc_context_destroy(context);  // frees the parsed IR + compiler too
            }
            memFree(raw);
        }
    }

    // ------------------------------------------------------------------
    // the two rules
    // ------------------------------------------------------------------

    private static void rejectBoundResources(long resources, String name, long context,
                                             MemoryStack stack) {
        for (int type : BOUND_RESOURCE_TYPES) {
            SpvcReflectedResource.Buffer list = listFor(resources, type, context, stack);
            if (list != null && list.remaining() > 0) {
                SpvcReflectedResource first = list.get(0);
                throw new RuntimeException("Effect shader '" + name + "' declares a bound resource ("
                        + describe(type) + " '" + first.nameString() + "'"
                        + (list.remaining() > 1 ? "', and " + (list.remaining() - 1) + " more" : "")
                        + "), but v1 effects bind no resources -- their only interface is the"
                        + " built-in push block (vec2 uResolution, vec2 uMouse, float uTime).");
            }
        }
    }

    private static void checkPushConstantSize(long compiler, long resources, String name,
                                              long context, MemoryStack stack) {
        SpvcReflectedResource.Buffer push =
                listFor(resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT, context, stack);
        if (push == null || push.remaining() == 0) {
            return;  // no push block declared -- allowed
        }
        long structType = spvc_compiler_get_type_handle(compiler, push.get(0).base_type_id());
        PointerBuffer pSize = stack.pointers(0);
        check(spvc_compiler_get_declared_struct_size(compiler, structType, pSize),
                "measure the push block of " + name, context);
        long size = pSize.get(0);
        if (size > EFFECT_PUSH_BYTES) {
            throw new RuntimeException("Effect shader '" + name + "' declares a " + size
                    + "-byte push_constant block, but jvre fills only the 20-byte built-in block"
                    + " (vec2 uResolution, vec2 uMouse, float uTime). Trim it to 20 bytes or fewer.");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The reflected-resource list for one category (null if empty / unavailable). */
    private static SpvcReflectedResource.Buffer listFor(long resources, int type, long context,
                                                        MemoryStack stack) {
        PointerBuffer pList = stack.pointers(0);
        PointerBuffer pCount = stack.pointers(0);
        check(spvc_resources_get_resource_list_for_type(resources, type, pList, pCount),
                "list resources of type " + type, context);
        long count = pCount.get(0);
        if (count == 0) {
            return null;
        }
        return SpvcReflectedResource.create(pList.get(0), (int) count);
    }

    /** A user-facing word for a resource category (for the error message). */
    private static String describe(int type) {
        if (type == SPVC_RESOURCE_TYPE_UNIFORM_BUFFER)         return "uniform buffer";
        if (type == SPVC_RESOURCE_TYPE_STORAGE_BUFFER)         return "storage buffer";
        if (type == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)          return "sampler";
        if (type == SPVC_RESOURCE_TYPE_SEPARATE_IMAGE)         return "texture";
        if (type == SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS)      return "sampler";
        if (type == SPVC_RESOURCE_TYPE_STORAGE_IMAGE)          return "storage image";
        if (type == SPVC_RESOURCE_TYPE_SUBPASS_INPUT)          return "subpass input";
        if (type == SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE) return "acceleration structure";
        return "bound resource";
    }

    /** Throw with SPIRV-Cross's own error string on a non-success result. */
    private static void check(int result, String what, long context) {
        if (result != SPVC_SUCCESS) {
            String detail = "";
            if (context != 0) {
                String last = spvc_context_get_last_error_string(context);
                if (last != null && !last.isBlank()) {
                    detail = ": " + last;
                }
            }
            throw new RuntimeException("SPIRV-Cross failed to " + what
                    + " (result " + result + ")" + detail);
        }
    }
}
