package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
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

    /**
     * Build a graphics pipeline from two SPIR-V classpath resources (compiled
     * from GLSL by the Gradle compileShaders task), rendering into color
     * attachment(s) of the given format.
     */
    public Pipeline(Device device, int colorFormat, String vertResource, String fragResource) {
        this.device = device;

        try (MemoryStack stack = stackPush()) {
            // ---- Shader modules: SPIR-V handed to the driver ----
            // A module is just a dumb container for the bytes; the actual
            // compile-for-this-GPU happens inside vkCreateGraphicsPipelines.
            // Once the pipeline exists the modules are dead weight, so they are
            // destroyed again at the end of this constructor.
            long vertModule = createShaderModule(vertResource);
            long fragModule = createShaderModule(fragResource);

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
            // Interleaved layout, 7 floats per vertex: [x y | r g b | u v]
            //   location 0 (vec2 inPosition) <- offset 0
            //   location 1 (vec3 inColor)    <- offset 8  (after 2 floats)
            //   location 2 (vec2 inUV)       <- offset 20 (after 5 floats)
            // (Hardcoded to jvre's one vertex layout for now; becomes a
            // parameter the moment a second layout exists.)
            VkVertexInputBindingDescription.Buffer binding =
                    VkVertexInputBindingDescription.calloc(1, stack);
            binding.binding(0);
            binding.stride(7 * Float.BYTES);
            binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributes =
                    VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).location(0).binding(0)
                    .format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributes.get(1).location(1).binding(0)
                    .format(VK_FORMAT_R32G32B32_SFLOAT).offset(2 * Float.BYTES);
            attributes.get(2).location(2).binding(0)
                    .format(VK_FORMAT_R32G32_SFLOAT).offset(5 * Float.BYTES);

            VkPipelineVertexInputStateCreateInfo vertexInput =
                    VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInput.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            vertexInput.pVertexBindingDescriptions(binding);
            vertexInput.pVertexAttributeDescriptions(attributes);

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
            // Culling OFF for the first triangle: a winding-order mistake with
            // culling on yields a silently empty screen -- the least debuggable
            // bug in graphics. Turned on (BACK) once things visibly work.
            VkPipelineRasterizationStateCreateInfo rasterizer =
                    VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);   // fill, not wireframe
            rasterizer.cullMode(VK_CULL_MODE_NONE);
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);  // moot with cull off
            rasterizer.lineWidth(1.0f);  // required even when not drawing lines

            // ---- Multisampling: off (1 sample/pixel) -- MSAA is a later treat ----
            VkPipelineMultisampleStateCreateInfo multisampling =
                    VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

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
            blendAttachment.blendEnable(true);
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

            // ---- Pipeline layout: the shader's external interface ----
            // Now holds BOTH data tiers: the descriptor set layout (binding 0,
            // the transform UBO) and the push-constant range (4 bytes of time,
            // FRAGMENT stage -- stageFlags must match where the shader's
            // push_constant block is consumed). Spec floor for push space: 128
            // bytes. (Hardcoded to jvre's one shader interface, like the
            // vertex layout: parameterized the moment shaders vary.)
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            pushRange.offset(0);
            pushRange.size(Float.BYTES);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            layoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
            layoutInfo.pPushConstantRanges(pushRange);

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
            pipelineInfo.pDynamicState(dynamicState);
            pipelineInfo.layout(layout);
            pipelineInfo.renderPass(VK_NULL_HANDLE);  // dynamic rendering: no render pass
            // (No depth/stencil state either -- nothing 3D to occlude yet.)

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
        System.out.println("Graphics pipeline created (" + vertResource + " + " + fragResource + ").");
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

    public void close() {
        vkDestroyPipeline(device.handle(), handle, null);
        vkDestroyPipelineLayout(device.handle(), layout, null);
        vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Load a compiled SPIR-V binary from the classpath and wrap it in a
     * VkShaderModule. Vulkan wants the bytes OFF-HEAP (it reads them through a
     * raw pointer), so they're copied into a memAlloc'd buffer and freed right
     * after the call -- the driver copies what it needs.
     */
    private long createShaderModule(String resourcePath) {
        byte[] bytes;
        try (InputStream in = Pipeline.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Shader resource not found on the classpath: "
                        + resourcePath + " (did compileShaders run?)");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource " + resourcePath, e);
        }

        ByteBuffer code = memAlloc(bytes.length);
        code.put(bytes).flip();
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(code);  // codeSize is taken from the buffer

            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateShaderModule(device.handle(), createInfo, null, pModule),
                    "Failed to create shader module for " + resourcePath);
            return pModule.get(0);
        } finally {
            memFree(code);
        }
    }
}
