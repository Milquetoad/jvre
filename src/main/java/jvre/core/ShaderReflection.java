package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.util.spvc.Spv.SpvDecorationBinding;
import static org.lwjgl.util.spvc.Spv.SpvDecorationDescriptorSet;
import static org.lwjgl.util.spvc.Spv.SpvDim2D;
import static org.lwjgl.util.spvc.Spv.SpvDim3D;
import static org.lwjgl.util.spvc.Spv.SpvDimCube;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_CUBE;

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
 *   1. NO descriptor-bound resources EXCEPT input-channel samplers. A {@code
 *      sampler2D} at set 0, binding 0..3 is an iChannel0..3 input (the renderer
 *      builds a matching sampler layout and feeds it). Any OTHER bound resource
 *      (UBO, storage buffer, storage image, separate image/sampler, ...) -- or a
 *      sampler outside set 0 / binding 0..3 -- is a guaranteed pipeline mismatch
 *      and is rejected. Binding decorations are part of the shader interface and
 *      survive optimization -- a reliable signal.
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

    /** The most input channels an effect may declare -- Shadertoy's iChannel0..3. */
    static final int MAX_EFFECT_CHANNELS = 4;

    /**
     * SPIRV-Cross resource categories that imply a descriptor binding the effect
     * pipeline can't satisfy -- every one is forbidden. {@code SAMPLED_IMAGE}
     * (a {@code sampler2D}) is deliberately ABSENT: those are the allowed
     * iChannel inputs, validated separately ({@link #reflectChannelCount}).
     */
    private static final int[] BOUND_RESOURCE_TYPES = {
            SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
            SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
            SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
            SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
            SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
            SPVC_RESOURCE_TYPE_SUBPASS_INPUT,
            SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE,
    };

    private ShaderReflection() {}  // static utility; no instances

    /**
     * Throw a clear, jvre-level error if the compiled fragment shader violates
     * the effect contract; otherwise return one {@code VkImageViewType} per input
     * CHANNEL it declares, indexed by binding (length = channel count,
     * 0..{@value #MAX_EFFECT_CHANNELS}). Each entry is {@code VIEW_TYPE_2D},
     * {@code _CUBE}, or {@code _3D} -- the dimensionality of the {@code samplerN}
     * at that binding -- so the renderer can bind a MATCHING texture (and reject a
     * mismatch in the user's terms). A gap (e.g. iChannel0 + iChannel2 declared,
     * 1 skipped) defaults to {@code VIEW_TYPE_2D}. An empty array means a classic
     * no-input effect.
     */
    static int[] checkEffectContract(byte[] spirv, String name) {
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
            return reflectChannels(compiler, resources, name, context, stack);
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

    /**
     * Validate the effect's input-channel samplers and reflect their dimensionality.
     * Each sampler must live at descriptor set 0, binding 0..3 (the iChannel0..3
     * convention); anything else is rejected. Returns one {@code VkImageViewType}
     * per channel indexed by binding, length {@code maxBinding + 1} (so a shader
     * using only iChannel0 and iChannel2 returns length 3, the unused middle slot
     * defaulting to 2D). Empty if no samplers.
     */
    private static int[] reflectChannels(long compiler, long resources, String name,
                                         long context, MemoryStack stack) {
        SpvcReflectedResource.Buffer samplers =
                listFor(resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, context, stack);
        if (samplers == null || samplers.remaining() == 0) {
            return new int[0];
        }
        int[] byBinding = new int[MAX_EFFECT_CHANNELS];
        Arrays.fill(byBinding, VK_IMAGE_VIEW_TYPE_2D);   // gaps default to 2D
        int maxBinding = -1;
        for (int i = 0; i < samplers.remaining(); i++) {
            SpvcReflectedResource r = samplers.get(i);
            int set = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationDescriptorSet);
            int binding = spvc_compiler_get_decoration(compiler, r.id(), SpvDecorationBinding);
            if (set != 0 || binding < 0 || binding >= MAX_EFFECT_CHANNELS) {
                throw new RuntimeException("Effect shader '" + name + "' declares a sampler '"
                        + r.nameString() + "' at set " + set + ", binding " + binding
                        + ", but effect input channels are iChannel0.." + (MAX_EFFECT_CHANNELS - 1)
                        + " -- a sampler at set 0, binding 0.." + (MAX_EFFECT_CHANNELS - 1) + ".");
            }
            // The sampler's dimensionality (2D / cube / 3D) is part of the image
            // base type -- reflect it so the renderer binds a matching texture.
            long imageType = spvc_compiler_get_type_handle(compiler, r.base_type_id());
            byBinding[binding] = viewTypeForDim(spvc_type_get_image_dimension(imageType),
                    name, r.nameString());
            maxBinding = Math.max(maxBinding, binding);
        }
        return Arrays.copyOf(byBinding, maxBinding + 1);
    }

    /** Map a SPIR-V image {@code Dim} to the VkImageViewType jvre binds for it;
     *  reject the dimensions jvre's channel system doesn't support (1D, rect, ...). */
    private static int viewTypeForDim(int dim, String name, String sampler) {
        if (dim == SpvDim2D)   return VK_IMAGE_VIEW_TYPE_2D;
        if (dim == SpvDimCube) return VK_IMAGE_VIEW_TYPE_CUBE;
        if (dim == SpvDim3D)   return VK_IMAGE_VIEW_TYPE_3D;
        throw new RuntimeException("Effect shader '" + name + "' declares channel sampler '"
                + sampler + "' with an unsupported dimensionality -- effect channels must be"
                + " sampler2D, samplerCube, or sampler3D.");
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
