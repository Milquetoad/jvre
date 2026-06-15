package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The GUARDED draw recorder a {@link SceneRenderer} receives -- a thin, jvre-owned
 * facade over the frame's command buffer. The user gets full draw control
 * (bind a pipeline, bind buffers, draw) WITHOUT touching the raw
 * {@code VkCommandBuffer} or any LWJGL Vulkan call: that keeps custom draw code
 * L1-clean and lets jvre keep owning the frame (it has already begun the render
 * pass and set the viewport/scissor before calling you).
 *
 * v1 (beat 1): bind a pipeline + a vertex buffer + draw. The resource-bound
 * methods (index buffers, push constants, descriptor sets) arrive with the next
 * beat. Instances are created by the Renderer per frame; never construct one.
 */
public final class FrameRenderer {

    private final VkCommandBuffer cmd;

    FrameRenderer(VkCommandBuffer cmd) {
        this.cmd = cmd;
    }

    /** Bind a pipeline created by {@link Renderer#createPipeline}. */
    public void bind(Pipeline pipeline) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
    }

    /** Bind a vertex buffer into binding 0 (the layout the bound pipeline expects). */
    public void bindVertexBuffer(Buffer buffer) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(buffer.handle()), stack.longs(0));
        }
    }

    /** Draw {@code vertexCount} vertices (non-indexed), one instance. */
    public void draw(int vertexCount) {
        vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    }
}
