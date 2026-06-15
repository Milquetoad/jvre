package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/**
 * A graphics pipeline: THE big bake. Vulkan compiles the shaders plus nearly
 * every piece of fixed-function state (primitive topology, rasterizer mode,
 * blending, ...) into ONE immutable object, up front. This is the deliberate
 * opposite of OpenGL's global state machine -- the driver sees the whole
 * configuration at build time, optimizes once, and at draw time a single
 * vkCmdBindPipeline swaps ALL of it atomically. The cost: state changes mean
 * building (and binding) a different pipeline.
 *
 * The escape hatch is DYNAMIC STATE: state declared dynamic here is excluded
 * from the bake and set per command buffer instead. We make viewport + scissor
 * dynamic so a window resize does NOT rebuild the pipeline.
 *
 * Built for [[Dynamic Rendering]]: instead of referencing a VkRenderPass, a
 * VkPipelineRenderingCreateInfo chained into pNext declares the attachment
 * FORMATS this pipeline will render into -- the only coupling that remains.
 *
 * Creative tier (unlike the infrastructure elementaries): Shader/Pipeline/
 * Buffer are the objects the L1 user will actually touch.
 */
public class Pipeline {

    private final Device device;
    private final long descriptorSetLayout;  // the SHAPE of the shader's bound resources
    private final long layout;               // VkPipelineLayout (set layout + push range)
    private final long handle;               // VkPipeline

    // CUSTOM-pipeline managed resources (the L1 escape hatch's bound uniforms).
    // When the spec declared a UBO, jvre owns a per-frame-in-flight UBO buffer +
    // descriptor set here (written once to point at the buffer); the user fills
    // the buffer each frame via FrameRenderer. Null for the built-in kinds and for
    // CUSTOM pipelines that declared no UBO.
    private Buffer[] uniformBuffers;         // [frame in flight]
    private long[] uniformSets;              // [frame in flight]
    private long uniformPool = VK_NULL_HANDLE;
    private int pushStageFlags = 0;          // the push-constant stage (CUSTOM)

    /**
     * Which of jvre's three pipeline shapes this is. The kinds differ only in a
     * handful of fixed-function choices -- vertex layout, cull, depth, blend,
     * bound resources, and the push range -- so one constructor body bakes all
     * three by branching on the kind. (This replaced a single {@code boolean
     * fullscreen} the instant a THIRD vertex layout -- the 2D shapes -- arrived:
     * exactly the "becomes a parameter the moment a second layout exists" note
     * the cube's vertex-input comment predicted.)
     */
    public enum Kind {
        /** 3D scene: [x y z | r g b | u v] verts, back-face cull, depth on, UBO + sampler. */
        SCENE,
        /** ShaderEffect: no vertex input (fullscreen triangle), no depth/resources, 20-byte frag push. */
        FULLSCREEN_EFFECT,
        /** L2 shapes: [x y | r g b a] verts, no cull/depth, blend on, no resources, 8-byte vertex push. */
        SHAPES_2D,
        /** User-defined (the L1 escape hatch): vertex layout + fixed-function from a {@link PipelineSpec}. */
        CUSTOM
    }

    /**
     * Build the SCENE pipeline from two SPIR-V classpath resources (compiled
     * from GLSL by the Gradle compileShaders task at build time), rendering
     * into color attachment(s) of the given format.
     */
    public Pipeline(Device device, int colorFormat, int depthFormat, int sampleCount,
                    String vertResource, String fragResource) {
        this(device, colorFormat, depthFormat, sampleCount,
                readResource(vertResource), readResource(fragResource),
                Kind.SCENE, vertResource + " + " + fragResource, null);
    }

    /**
     * Build a USER-DEFINED pipeline (the L1 escape hatch) from a {@link
     * PipelineSpec}. The caller supplies shaders + vertex layout + fixed-function
     * choices; the swapchain formats + sample count are passed in by {@link
     * Renderer#createPipeline}. Funnels through the same bake as the built-in
     * kinds. v1: no descriptors / push constants.
     */
    public static Pipeline fromSpec(Device device, int colorFormat, int depthFormat,
                                    int sampleCount, PipelineSpec spec, int framesInFlight) {
        Pipeline p = new Pipeline(device, colorFormat, depthFormat, sampleCount,
                spec.vertexSpirv, spec.fragmentSpirv, Kind.CUSTOM, spec.label, spec);
        if (spec.uniformBufferSize > 0) {
            p.allocateUniformResources(framesInFlight, spec.uniformBufferSize);
        }
        return p;
    }

    /**
     * Build a FULLSCREEN-EFFECT pipeline (the ShaderEffect path) from raw
     * SPIR-V bytes -- the fragment half typically compiled at RUNTIME by
     * {@link ShaderCompiler}. Every difference from the scene pipeline flows
     * from "one screen-covering triangle, no geometry":
     *   - NO vertex input (the triangle is generated from gl_VertexIndex in
     *     the vertex shader -- there are no buffers to describe);
     *   - cull NONE (a single known triangle -- no winding question at all);
     *   - depth test/write OFF (nothing 3D; the depth attachment FORMAT is
     *     still declared because the render pass instance carries one);
     *   - no descriptor set layout (v1 effects bind no resources);
     *   - a 20-byte fragment push range (uResolution, uMouse, uTime) instead
     *     of the scene's 4 bytes of time;
     *   - no blending (the effect writes every pixel, opaquely).
     */
    public static Pipeline fullscreenEffect(Device device, int colorFormat, int depthFormat,
                                            int sampleCount, byte[] vertSpirv, byte[] fragSpirv,
                                            String label) {
        return new Pipeline(device, colorFormat, depthFormat, sampleCount,
                vertSpirv, fragSpirv, Kind.FULLSCREEN_EFFECT, label, null);
    }

    /**
     * Build the L2 SHAPES-2D pipeline from two SPIR-V classpath resources. It
     * sits between the other two: it HAS a vertex layout (interleaved
     * {@code [x y | r g b a]}, position in pixels) like the scene, but is a flat
     * 2D pass like the effect -- cull NONE, depth OFF, no descriptors. Blend is
     * ON (translucent shapes), and the push range is 8 bytes at the VERTEX stage
     * (vec2 uResolution, for the pixels->NDC conversion the shape vertex shader
     * does).
     */
    public static Pipeline shapes2D(Device device, int colorFormat, int depthFormat,
                                    int sampleCount, String vertResource, String fragResource) {
        return new Pipeline(device, colorFormat, depthFormat, sampleCount,
                readResource(vertResource), readResource(fragResource),
                Kind.SHAPES_2D, vertResource + " + " + fragResource, null);
    }

    private Pipeline(Device device, int colorFormat, int depthFormat, int sampleCount,
                     byte[] vertSpirv, byte[] fragSpirv, Kind kind, String label,
                     PipelineSpec spec) {
        this.device = device;

        try (MemoryStack stack = stackPush()) {
            // ---- Shader modules: SPIR-V handed to the driver ----
            // A module is just a dumb container for the bytes; the actual
            // compile-for-this-GPU happens inside vkCreateGraphicsPipelines.
            // Once the pipeline exists the modules are dead weight, so they are
            // destroyed again at the end of this constructor.
            long vertModule = createShaderModule(vertSpirv, label);
            long fragModule = createShaderModule(fragSpirv, label);

            // ---- Stages: which module runs at which programmable stage ----
            // pName is the entry point -- "main" by convention; one module could
            // hold several entry points.
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            stages.get(0).stage(VK_SHADER_STAGE_VERTEX_BIT);
            stages.get(0).module(vertModule);
            stages.get(0).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            stages.get(1).stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            stages.get(1).module(fragModule);
            stages.get(1).pName(stack.UTF8("main"));

            // ---- Vertex input: how the bound vertex buffer is sliced up ----
            // Two halves:
            //   BINDING = the buffer slot. We use one (binding 0), advancing by
            //   STRIDE bytes per vertex (per-instance data would use RATE_INSTANCE).
            //   ATTRIBUTES = what each shader `location` reads out of a binding.
            //   Formats double as "data shapes": R32G32_SFLOAT = vec2, etc.
            // Interleaved layout, 8 floats per vertex: [x y z | r g b | u v]
            //   location 0 (vec3 inPosition) <- offset 0
            //   location 1 (vec3 inColor)    <- offset 12 (after 3 floats)
            //   location 2 (vec2 inUV)       <- offset 24 (after 6 floats)
            // Position is vec3 (3D); R32G32B32_SFLOAT = vec3. This WAS "jvre's one
            // vertex layout" until the 2D shapes added a second -- the layout is
            // now chosen by Kind (the moment the old comment anticipated).
            VkPipelineVertexInputStateCreateInfo vertexInput =
                    VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInput.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            if (kind == Kind.SCENE) {
                // Cube: [x y z | r g b | u v], 8 floats/vertex.
                VkVertexInputBindingDescription.Buffer binding =
                        VkVertexInputBindingDescription.calloc(1, stack);
                binding.binding(0);
                binding.stride(8 * Float.BYTES);
                binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attributes =
                        VkVertexInputAttributeDescription.calloc(3, stack);
                attributes.get(0).location(0).binding(0)
                        .format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attributes.get(1).location(1).binding(0)
                        .format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * Float.BYTES);
                attributes.get(2).location(2).binding(0)
                        .format(VK_FORMAT_R32G32_SFLOAT).offset(6 * Float.BYTES);

                vertexInput.pVertexBindingDescriptions(binding);
                vertexInput.pVertexAttributeDescriptions(attributes);
            } else if (kind == Kind.SHAPES_2D) {
                // L2 shapes: [x y | r g b a | localX localY | halfX halfY |
                // cornerRadius | u v | mode], 14 floats/vertex. pos (loc 0) +
                // color (loc 1) every shape uses; local (loc 2) + half (loc 3) +
                // cornerRadius (loc 4) drive the SDF rounded-box coverage path;
                // uv (loc 5) is the texture coordinate (image/text); mode (loc 6)
                // SELECTS what the fragment shader does -- 0 flat, 1 SDF box,
                // 2 textured image, 3 SDF text. One layout for every shape kind,
                // so they share a batch and keep their draw order.
                VkVertexInputBindingDescription.Buffer binding =
                        VkVertexInputBindingDescription.calloc(1, stack);
                binding.binding(0);
                binding.stride(14 * Float.BYTES);
                binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attributes =
                        VkVertexInputAttributeDescription.calloc(7, stack);
                attributes.get(0).location(0).binding(0)
                        .format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                attributes.get(1).location(1).binding(0)
                        .format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(2 * Float.BYTES);
                attributes.get(2).location(2).binding(0)
                        .format(VK_FORMAT_R32G32_SFLOAT).offset(6 * Float.BYTES);
                attributes.get(3).location(3).binding(0)
                        .format(VK_FORMAT_R32G32_SFLOAT).offset(8 * Float.BYTES);
                attributes.get(4).location(4).binding(0)
                        .format(VK_FORMAT_R32_SFLOAT).offset(10 * Float.BYTES);
                attributes.get(5).location(5).binding(0)
                        .format(VK_FORMAT_R32G32_SFLOAT).offset(11 * Float.BYTES);
                attributes.get(6).location(6).binding(0)
                        .format(VK_FORMAT_R32_SFLOAT).offset(13 * Float.BYTES);

                vertexInput.pVertexBindingDescriptions(binding);
                vertexInput.pVertexAttributeDescriptions(attributes);
            } else if (kind == Kind.CUSTOM) {
                // User-defined: one binding (binding 0) with the user's stride +
                // attributes, translated from the VertexLayout.
                VertexLayout layout = spec.vertexLayout;
                VkVertexInputBindingDescription.Buffer binding =
                        VkVertexInputBindingDescription.calloc(1, stack);
                binding.binding(0);
                binding.stride(layout.stride());
                binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

                java.util.List<VertexLayout.Attribute> attrs = layout.attributes();
                VkVertexInputAttributeDescription.Buffer attributes =
                        VkVertexInputAttributeDescription.calloc(attrs.size(), stack);
                for (int i = 0; i < attrs.size(); i++) {
                    VertexLayout.Attribute a = attrs.get(i);
                    attributes.get(i).location(a.location).binding(0)
                            .format(a.format.vk).offset(a.offset);
                }
                vertexInput.pVertexBindingDescriptions(binding);
                vertexInput.pVertexAttributeDescriptions(attributes);
            }
            // (FULLSCREEN_EFFECT: left EMPTY -- zero bindings, zero attributes.
            // The vertex shader reads only gl_VertexIndex; "no vertex buffer" is
            // a pipeline-level fact, not just an unbound buffer.)

            // ---- Input assembly: how vertices group into primitives ----
            // TRIANGLE_LIST: every 3 consecutive vertices = one independent triangle.
            VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                    VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            // ---- Viewport state: COUNTS only -- the values are dynamic ----
            VkPipelineViewportStateCreateInfo viewportState =
                    VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ---- Rasterizer: triangles -> fragments ----
            // BACK-FACE CULLING is on: for a closed opaque mesh you can never see
            // a face whose outside points away, and the rasterizer can drop those
            // triangles by their on-screen WINDING before any fragment work --
            // half the cube's triangles skipped per frame, for free. (It stayed
            // OFF until the cube visibly worked: a winding mistake with culling on
            // is a silently empty screen, the least debuggable bug in graphics.)
            //
            // Why COUNTER_CLOCKWISE front faces? TWO mirrors are in play, and they
            // CANCEL: (1) Vulkan's y-DOWN NDC/framebuffer is itself a mirror
            // relative to the GL conventions the projection math comes from --
            // alone it would flip our model-space CCW-from-outside faces to CW on
            // screen; (2) the projection's negated m11 (the Y-flip) is a second
            // mirror on top. Mirror x mirror = no net flip, so the authored CCW
            // winding survives to the screen. Settled EMPIRICALLY: the first
            // attempt reasoned "one mirror -> CW", and the cube rendered
            // inside-out -- the unmissable symptom of culling the front faces.
            // (The famous "flipping proj[1][1] forces a frontFace change" gotcha
            // is real, but it's relative to NOT flipping -- it doesn't make CW
            // Vulkan's natural front.)
            VkPipelineRasterizationStateCreateInfo rasterizer =
                    VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);   // fill, not wireframe
            // Only the 3D scene culls. The flat passes (effect, 2D shapes) draw
            // known-facing geometry, so culling buys nothing and risks everything.
            rasterizer.cullMode(kind == Kind.SCENE ? VK_CULL_MODE_BACK_BIT
                    : kind == Kind.CUSTOM ? spec.cull.vk : VK_CULL_MODE_NONE);
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);  // two mirrors cancel
            rasterizer.lineWidth(1.0f);  // required even when not drawing lines

            // ---- Multisampling: MSAA, baked ----
            // rasterizationSamples must match the attachments rendered into (the
            // Swapchain's MSAA color target + the multisampled depth buffer).
            // This being BAKED pipeline state is exactly why AA is a
            // creation-time option at L2, never a runtime toggle (the L2 spec
            // pinned that before this code existed).
            VkPipelineMultisampleStateCreateInfo multisampling =
                    VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.rasterizationSamples(sampleCount);

            // ---- Depth/stencil: test + write depth, LESS keeps the nearer ----
            // The depth buffer is cleared to 1.0 (far) each frame; a fragment
            // passes when its depth is LESS than what's stored, then writes its own
            // depth. That is what makes a solid's near faces hide its far faces
            // (visible once beat 3 draws a cube). No stencil. The depth attachment
            // FORMAT is declared below in the dynamic-rendering create-info.
            VkPipelineDepthStencilStateCreateInfo depthStencil =
                    VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            // Only the 3D scene tests/writes depth; the flat passes have nothing
            // to occlude. (The attachment FORMAT below is declared regardless --
            // the render pass instance always carries a depth attachment.)
            depthStencil.depthTestEnable(kind == Kind.SCENE || (kind == Kind.CUSTOM && spec.depthTest));
            depthStencil.depthWriteEnable(kind == Kind.SCENE || (kind == Kind.CUSTOM && spec.depthWrite));
            depthStencil.depthCompareOp(VK_COMPARE_OP_LESS);
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.stencilTestEnable(false);

            // ---- Color blending: straight ALPHA blending (src "over" dst) ----
            // One attachment state per color attachment. Per channel the hardware
            // computes  result = src*srcFactor <op> dst*dstFactor. The classic
            // "source over destination" (transparency):
            //   color = src.rgb*src.a + dst.rgb*(1 - src.a)        [ADD]
            //   alpha = src.a*1       + dst.a*(1 - src.a)
            // so a fragment with alpha 0 leaves the background untouched and alpha
            // 1 fully replaces it -- the sprite transparency seam. The framebuffer
            // is sRGB, so the GPU blends in LINEAR space and re-encodes on store
            // (gamma-correct blending for free); the alpha of an _SRGB format is
            // itself linear, so src.a is the raw texel value.
            // (Straight, not premultiplied, alpha: fine here -- NEAREST + binary
            // texture alpha means no partial-alpha edge texels to fringe. Filtered
            // or mipmapped sprites would prefer premultiplied to avoid dark halos.)
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            // Scene + 2D shapes alpha-blend (translucency); only the fullscreen
            // effect writes every pixel opaquely (REPLACE -- factors below ignored).
            blendAttachment.blendEnable(kind == Kind.CUSTOM ? spec.blend : kind != Kind.FULLSCREEN_EFFECT);
            blendAttachment.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA);
            blendAttachment.dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
            blendAttachment.colorBlendOp(VK_BLEND_OP_ADD);
            blendAttachment.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE);
            blendAttachment.dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
            blendAttachment.alphaBlendOp(VK_BLEND_OP_ADD);
            blendAttachment.colorWriteMask(
                    VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                    | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

            VkPipelineColorBlendStateCreateInfo colorBlend =
                    VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlend.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlend.pAttachments(blendAttachment);

            // ---- Dynamic state: what the bake EXCLUDES ----
            // Viewport + scissor get set per command buffer (recordCommandBuffer),
            // so swapchain recreation never touches this pipeline.
            VkPipelineDynamicStateCreateInfo dynamicState =
                    VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicState.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
            dynamicState.pDynamicStates(stack.ints(
                    VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            // ---- Descriptor set layout: the SHAPE of what the shader binds ----
            // Not actual resources -- a schema. TWO bindings now:
            //   binding 0 = one UNIFORM BUFFER, vertex stage         (the transform)
            //   binding 1 = one COMBINED_IMAGE_SAMPLER, fragment stage (the texture)
            // COMBINED_IMAGE_SAMPLER bundles an image VIEW + a SAMPLER into a
            // single descriptor -- GLSL's `sampler2D`. (Vulkan also offers
            // SEPARATE sampled-image + sampler descriptors; combined is the
            // common, simplest case.) Descriptor SETS (allocated by the Renderer)
            // are instances of this shape, pointing at real buffers/images.
            // Identically-defined layouts are compatible, so sets survive a
            // pipeline rebuild (the resize format-change path).
            if (kind == Kind.FULLSCREEN_EFFECT
                    || (kind == Kind.CUSTOM && spec.uniformBufferSize == 0)) {
                // The effect's whole interface is the push block; a CUSTOM pipeline
                // with no declared UBO binds no resources. Either way, no layout.
                descriptorSetLayout = VK_NULL_HANDLE;
            } else if (kind == Kind.CUSTOM) {
                // User-defined: one UBO at binding 0 @ the declared stage.
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(1, stack);
                bindings.get(0).binding(0);
                bindings.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                bindings.get(0).descriptorCount(1);
                bindings.get(0).stageFlags(spec.uniformStage.vk);

                VkDescriptorSetLayoutCreateInfo setLayoutInfo =
                        VkDescriptorSetLayoutCreateInfo.calloc(stack);
                setLayoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
                setLayoutInfo.pBindings(bindings);

                LongBuffer pSetLayout = stack.longs(VK_NULL_HANDLE);
                Vk.check(vkCreateDescriptorSetLayout(device.handle(), setLayoutInfo, null, pSetLayout),
                        "Failed to create the custom pipeline descriptor set layout");
                descriptorSetLayout = pSetLayout.get(0);
            } else if (kind == Kind.SHAPES_2D) {
                // L2 shapes bind ONE resource: the texture that image (mode 2)
                // and text (mode 3) sample. binding 0 = a COMBINED_IMAGE_SAMPLER
                // in the FRAGMENT stage. Flat/SDF shapes (modes 0/1) never read
                // it, but it must still be a VALID bound descriptor -- the shader
                // references the sampler statically -- so the Renderer always
                // binds something (a 1x1 white default when no image is drawn).
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(1, stack);
                bindings.get(0).binding(0);                                  // = shader binding 0
                bindings.get(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                bindings.get(0).descriptorCount(1);
                bindings.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

                VkDescriptorSetLayoutCreateInfo setLayoutInfo =
                        VkDescriptorSetLayoutCreateInfo.calloc(stack);
                setLayoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
                setLayoutInfo.pBindings(bindings);

                LongBuffer pSetLayout = stack.longs(VK_NULL_HANDLE);
                Vk.check(vkCreateDescriptorSetLayout(device.handle(), setLayoutInfo, null, pSetLayout),
                        "Failed to create the shape descriptor set layout");
                descriptorSetLayout = pSetLayout.get(0);
            } else {  // SCENE (the cube): a UBO + a texture
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(2, stack);

                bindings.get(0).binding(0);                                  // = shader binding 0
                bindings.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                bindings.get(0).descriptorCount(1);                         // arrays exist; we bind one
                bindings.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);

                bindings.get(1).binding(1);                                  // = shader binding 1
                bindings.get(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                bindings.get(1).descriptorCount(1);
                bindings.get(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

                VkDescriptorSetLayoutCreateInfo setLayoutInfo =
                        VkDescriptorSetLayoutCreateInfo.calloc(stack);
                setLayoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
                setLayoutInfo.pBindings(bindings);

                LongBuffer pSetLayout = stack.longs(VK_NULL_HANDLE);
                Vk.check(vkCreateDescriptorSetLayout(device.handle(), setLayoutInfo, null, pSetLayout),
                        "Failed to create the descriptor set layout");
                descriptorSetLayout = pSetLayout.get(0);
            }

            // ---- Pipeline layout: the shader's external interface ----
            // Now holds BOTH data tiers: the descriptor set layout (binding 0,
            // the transform UBO) and the push-constant range (4 bytes of time,
            // FRAGMENT stage -- stageFlags must match where the shader's
            // push_constant block is consumed). Spec floor for push space: 128
            // bytes. (Hardcoded to jvre's one shader interface, like the
            // vertex layout: parameterized the moment shaders vary.)
            // Each kind's push interface differs in STAGE and SIZE:
            //   SCENE             -> 4 bytes  @ fragment (the time pulse)
            //   FULLSCREEN_EFFECT -> 20 bytes @ fragment (uResolution/uMouse/uTime)
            //   SHAPES_2D         -> 8 bytes  @ vertex   (uResolution, for px->NDC)
            // (Spec floor for push space is 128 bytes; all three are well under.)
            int pushSize = switch (kind) {
                case SCENE -> Float.BYTES;
                case FULLSCREEN_EFFECT -> 5 * Float.BYTES;
                case SHAPES_2D -> 2 * Float.BYTES;
                case CUSTOM -> spec.pushSize;   // 0 = none
            };
            VkPushConstantRange.Buffer pushRange = null;
            if (pushSize > 0) {
                int stageFlags = kind == Kind.CUSTOM ? spec.pushStage.vk
                        : kind == Kind.SHAPES_2D ? VK_SHADER_STAGE_VERTEX_BIT
                        : VK_SHADER_STAGE_FRAGMENT_BIT;
                this.pushStageFlags = stageFlags;
                pushRange = VkPushConstantRange.calloc(1, stack);
                pushRange.stageFlags(stageFlags);
                pushRange.offset(0);
                pushRange.size(pushSize);
            }

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            if (descriptorSetLayout != VK_NULL_HANDLE) {
                layoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
            }
            if (pushRange != null) {
                layoutInfo.pPushConstantRanges(pushRange);
            }

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreatePipelineLayout(device.handle(), layoutInfo, null, pLayout),
                    "Failed to create the pipeline layout");
            layout = pLayout.get(0);

            // ---- Dynamic rendering hookup: declare attachment FORMATS ----
            // Classic Vulkan baked a VkRenderPass reference into the pipeline
            // (the coupling dynamic rendering exists to remove). What remains is
            // this: the formats of the attachments we'll render into, chained
            // via pNext. Must match what vkCmdBeginRendering is given later.
            VkPipelineRenderingCreateInfo renderingInfo =
                    VkPipelineRenderingCreateInfo.calloc(stack);
            renderingInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO);
            renderingInfo.pColorAttachmentFormats(stack.ints(colorFormat));
            renderingInfo.depthAttachmentFormat(depthFormat);  // must match the depth attachment

            // ---- The bake ----
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                    VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pNext(renderingInfo.address());
            pipelineInfo.pStages(stages);
            pipelineInfo.pVertexInputState(vertexInput);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pColorBlendState(colorBlend);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pDynamicState(dynamicState);
            pipelineInfo.layout(layout);
            pipelineInfo.renderPass(VK_NULL_HANDLE);  // dynamic rendering: no render pass

            // Takes an ARRAY of create-infos + an optional VkPipelineCache --
            // built for creating many pipelines at once; we make one, uncached.
            LongBuffer pPipeline = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE,
                            pipelineInfo, null, pPipeline),
                    "Failed to create the graphics pipeline");
            handle = pPipeline.get(0);

            // Modules served their purpose (the SPIR-V is compiled INTO the
            // pipeline now) -- destroy immediately.
            vkDestroyShaderModule(device.handle(), vertModule, null);
            vkDestroyShaderModule(device.handle(), fragModule, null);
        }
        System.out.println("Graphics pipeline created (" + label + ").");
    }

    /** The VkPipeline handle -- for vkCmdBindPipeline. */
    public long handle() {
        return handle;
    }

    /** The VkPipelineLayout -- push constants and descriptor binds go against it. */
    public long layout() {
        return layout;
    }

    /** The descriptor set layout -- the Renderer allocates its sets from this shape. */
    public long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    // ---- CUSTOM-pipeline managed uniforms (read by FrameRenderer) ----

    /** True if this (CUSTOM) pipeline declared a UBO -- so a descriptor set exists to bind. */
    boolean hasUniforms() {
        return uniformSets != null;
    }

    /** This frame's UBO descriptor set (binding 0). */
    long uniformSet(int frame) {
        return uniformSets[frame];
    }

    /** Write this frame's UBO buffer (host-visible; fence-guarded by the caller). */
    void uploadUniform(int frame, float[] data) {
        uniformBuffers[frame].uploadFloats(data);
    }

    /** The push-constant stage flags (0 if none declared). */
    int pushStageFlags() {
        return pushStageFlags;
    }

    /**
     * Allocate the per-frame UBO buffers + descriptor sets for a CUSTOM pipeline
     * that declared a uniform buffer. One host-visible UBO + one set per frame in
     * flight; each set is written ONCE to point at its buffer (the user rewrites
     * the buffer's CONTENTS per frame). Self-owned -- freed in {@link #close}.
     */
    private void allocateUniformResources(int frames, int uboSize) {
        uniformBuffers = new Buffer[frames];
        for (int i = 0; i < frames; i++) {
            uniformBuffers[i] = new Buffer(device, uboSize, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
        }
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            poolSizes.get(0).descriptorCount(frames);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(frames);
            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool),
                    "Failed to create the custom pipeline descriptor pool");
            uniformPool = pPool.get(0);

            LongBuffer layouts = stack.mallocLong(frames);
            for (int i = 0; i < frames; i++) {
                layouts.put(descriptorSetLayout);
            }
            layouts.flip();
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(uniformPool);
            allocInfo.pSetLayouts(layouts);
            LongBuffer pSets = stack.mallocLong(frames);
            Vk.check(vkAllocateDescriptorSets(device.handle(), allocInfo, pSets),
                    "Failed to allocate custom pipeline descriptor sets");

            uniformSets = new long[frames];
            for (int i = 0; i < frames; i++) {
                uniformSets[i] = pSets.get(i);
                VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufInfo.buffer(uniformBuffers[i].handle());
                bufInfo.offset(0);
                bufInfo.range(VK_WHOLE_SIZE);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
                w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                w.get(0).dstSet(uniformSets[i]);
                w.get(0).dstBinding(0);
                w.get(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                w.get(0).descriptorCount(1);
                w.get(0).pBufferInfo(bufInfo);
                vkUpdateDescriptorSets(device.handle(), w, null);
            }
        }
    }

    public void close() {
        if (uniformBuffers != null) {
            for (Buffer b : uniformBuffers) {
                b.close();
            }
        }
        if (uniformPool != VK_NULL_HANDLE) {   // frees its descriptor sets too
            vkDestroyDescriptorPool(device.handle(), uniformPool, null);
        }
        vkDestroyPipeline(device.handle(), handle, null);
        vkDestroyPipelineLayout(device.handle(), layout, null);
        if (descriptorSetLayout != VK_NULL_HANDLE) {  // fullscreen pipelines have none
            vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Read a compiled SPIR-V binary from the classpath (jvre's own build-time
     * shaders -- the scene pair, the fullscreen-triangle vertex shader).
     * Package-visible: the Renderer uses it to fetch the fullscreen vert for
     * effect pipelines.
     */
    static byte[] readResource(String resourcePath) {
        try (InputStream in = Pipeline.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Shader resource not found on the classpath: "
                        + resourcePath + " (did compileShaders run?)");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource " + resourcePath, e);
        }
    }

    /**
     * Wrap SPIR-V bytes in a VkShaderModule -- the same call whether the bytes
     * came from a build-time .spv resource or runtime shaderc ({@link
     * ShaderCompiler}); the driver cannot tell the difference. Vulkan wants the
     * bytes OFF-HEAP (it reads them through a raw pointer), so they're copied
     * into a memAlloc'd buffer and freed right after -- the driver copies what
     * it needs.
     */
    private long createShaderModule(byte[] bytes, String label) {
        ByteBuffer code = memAlloc(bytes.length);
        code.put(bytes).flip();
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(code);  // codeSize is taken from the buffer

            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateShaderModule(device.handle(), createInfo, null, pModule),
                    "Failed to create shader module for " + label);
            return pModule.get(0);
        } finally {
            memFree(code);
        }
    }
}
