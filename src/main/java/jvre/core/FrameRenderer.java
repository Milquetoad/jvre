package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The GUARDED draw recorder a {@link SceneRenderer} receives -- a thin, jvre-owned
 * facade over the frame's command buffer. The user gets full draw control (bind a
 * pipeline, bind buffers, set uniforms/push constants, draw) WITHOUT touching the
 * raw {@code VkCommandBuffer} or any LWJGL Vulkan call: custom draw code stays
 * L1-clean and jvre keeps owning the frame (it has begun the render pass + set the
 * viewport/scissor before calling you).
 *
 * Bound resources: if the pipeline declared a UBO ({@link PipelineSpec}), {@link
 * #bind} auto-binds this frame's descriptor set, and {@link #uniform}/{@link
 * #pushConstants} fill the data -- jvre owns the descriptor/per-frame-buffer
 * plumbing. Instances are created by the Renderer per frame; never construct one.
 */
public final class FrameRenderer {

    private final VkCommandBuffer cmd;
    private final int frame;       // which frame-in-flight slot (selects the UBO/set)
    private Pipeline bound;        // the last-bound pipeline (target for uniform/push)

    FrameRenderer(VkCommandBuffer cmd, int frame) {
        this.cmd = cmd;
        this.frame = frame;
    }

    /** Bind a pipeline created by {@link Renderer#createPipeline}. If it declared a
     *  UBO, this frame's descriptor set is bound automatically. */
    public void bind(Pipeline pipeline) {
        bound = pipeline;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        if (pipeline.hasDescriptorSet()) {
            try (MemoryStack stack = stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.layout(), 0, stack.longs(pipeline.uniformSet(frame)), null);
            }
        }
    }

    /** Bind a vertex buffer into binding 0 (the layout the bound pipeline expects). */
    public void bindVertexBuffer(Buffer buffer) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(buffer.handle()), stack.longs(0));
        }
    }

    /** Bind a UINT16 index buffer (from {@link Renderer#createIndexBuffer}) for an
     *  indexed draw -- so shared vertices aren't duplicated. */
    public void bindIndexBuffer(Buffer buffer) {
        vkCmdBindIndexBuffer(cmd, buffer.handle(), 0, VK_INDEX_TYPE_UINT16);
    }

    /** Write the bound pipeline's UBO for this frame (it declared one via {@link
     *  PipelineSpec#builder uniformBuffer}). Floats are column-major for a matrix. */
    public void uniform(float[] data) {
        requireBound("uniform");
        bound.uploadUniform(frame, data);
    }

    /** Set the bound pipeline's texture for this frame (it declared one via {@link
     *  PipelineSpec#builder texture}). */
    public void texture(Texture tex) {
        requireBound("texture");
        bound.uploadTexture(frame, tex);
    }

    /** Set the bound pipeline's push constants for this draw (it declared a range). */
    public void pushConstants(float[] data) {
        requireBound("pushConstants");
        try (MemoryStack stack = stackPush()) {
            vkCmdPushConstants(cmd, bound.layout(), bound.pushStageFlags(), 0, stack.floats(data));
        }
    }

    /** Draw {@code vertexCount} vertices (non-indexed), one instance. */
    public void draw(int vertexCount) {
        vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    }

    /** Draw {@code indexCount} indices from the bound index buffer, one instance. */
    public void drawIndexed(int indexCount) {
        vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
    }

    private void requireBound(String call) {
        if (bound == null) {
            throw new IllegalStateException(call + "() called before bind()");
        }
    }
}
