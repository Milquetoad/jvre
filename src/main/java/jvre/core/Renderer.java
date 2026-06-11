package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.lwjgl.vulkan.VkViewport;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The coordinator of the recreatable "device context": OWNS the Device and the
 * Swapchain (it constructs them), plus everything needed to get a frame onto
 * the screen -- command pool/buffers, sync objects, and the per-frame draw.
 *
 * The ownership is the point: Instance + Surface live ABOVE this class and stay
 * put for the life of the app, while everything GPU-dependent hangs off the
 * Renderer. Switching GPUs (future) = tear down the Renderer, build a new one.
 * Resizing = the Renderer rebuilds its own swapchain-dependent slice.
 *
 * Infrastructure tier: an L2 user never touches this directly; the L1 facade
 * will construct one behind the scenes.
 */
public class Renderer {

    // Vulkan's "wait forever" timeout sentinel (UINT64_MAX) for fences/acquire.
    private static final long NO_TIMEOUT = 0xFFFFFFFFFFFFFFFFL;

    // Kept for swapchain recreation: rebuilding negotiates against the live
    // surface and the window's current framebuffer size.
    private final Surface surface;
    private final Window window;

    // The demo triangle's shaders, compiled GLSL -> SPIR-V by the Gradle
    // compileShaders task and loaded from the classpath. Becomes real API once
    // users bring their own shaders (the L2 Shadertoy altitude).
    private static final String TRIANGLE_VERT = "/shaders/triangle.vert.spv";
    private static final String TRIANGLE_FRAG = "/shaders/triangle.frag.spv";

    // The demo triangle's GEOMETRY -- interleaved, 5 floats per vertex,
    // matching Pipeline's binding/attribute descriptions: [x y | r g b].
    // NDC coordinates (y DOWN). Like the clear color and shaders: demo content,
    // destined to become API (L1 users hand us their geometry).
    private static final float[] TRIANGLE_VERTICES = {
            //   x      y      r     g     b
             0.0f, -0.5f,  1.0f, 0.0f, 0.0f,   // top center, red
             0.5f,  0.5f,  0.0f, 1.0f, 0.0f,   // bottom right, green
            -0.5f,  0.5f,  0.0f, 0.0f, 1.0f,   // bottom left, blue
    };

    private final Device device;
    private Swapchain swapchain;
    private Pipeline pipeline;
    private Buffer vertexBuffer;

    // The clear color (RGBA in [0,1]); becomes real API once there is more to
    // render than a clear. The swapchain is an sRGB format, so these linear
    // values are sRGB-encoded on write.
    private final float clearR;
    private final float clearG;
    private final float clearB;

    // How many frames the CPU may be preparing AHEAD of the GPU. With 1, CPU and
    // GPU take turns idling (CPU waits for the frame to finish before recording
    // the next). With 2, the CPU records frame N+1 while the GPU draws frame N --
    // the standard sweet spot. Higher adds input latency for little throughput.
    private static final int MAX_FRAMES_IN_FLIGHT = 2;

    // Command pool: allocator for command buffers, tied to one queue family
    // (graphics). Each frame in flight owns ONE buffer, re-recorded every frame
    // (the modern model -- a real scene changes every frame, and the target
    // image isn't known until acquire). Destroying the pool frees the buffers.
    private long commandPool = VK_NULL_HANDLE;
    private VkCommandBuffer[] commandBuffers;   // [frame in flight], NOT [image]

    // Synchronization, one set per frame in flight: the semaphore orders
    // GPU<->GPU steps; the fence lets the CPU know that frame's GPU work is done
    // so its command buffer + semaphore are safe to reuse.
    private long[] imageAvailableSemaphores;    // [frame] image ready to draw into
    private long[] inFlightFences;              // [frame] GPU finished this frame
    // ONE renderFinished semaphore PER swapchain image -- indexed by ACQUIRED
    // IMAGE, not by frame: a per-frame one races with presentation
    // (validation VUID-...-00067).
    private long[] renderFinishedSemaphores;    // [image] render done, safe to present

    // Which frame-in-flight slot we're on (cycles 0..MAX_FRAMES_IN_FLIGHT-1).
    private int currentFrame = 0;

    public Renderer(Instance instance, Surface surface, Window window,
                    float clearR, float clearG, float clearB) {
        this.surface = surface;
        this.window = window;
        this.clearR = clearR;
        this.clearG = clearG;
        this.clearB = clearB;

        // The device context, top to bottom. The pipeline needs the swapchain's
        // image format (dynamic rendering's one remaining coupling).
        this.device = new Device(instance, surface);
        this.swapchain = new Swapchain(device, surface, window);
        this.pipeline = new Pipeline(device, swapchain.imageFormat(), TRIANGLE_VERT, TRIANGLE_FRAG);

        // Pool first: the vertex-buffer upload below records a one-shot
        // transfer command buffer from it.
        createCommandPool();

        // The vertex buffer, in DEVICE_LOCAL memory (VRAM) via a staging
        // upload -- static geometry belongs where the GPU reads fastest. (The
        // first version of this used plain HOST_VISIBLE memory: simpler, but
        // the GPU then re-reads it over the bus every frame.)
        this.vertexBuffer = Buffer.deviceLocal(device, commandPool,
                TRIANGLE_VERTICES, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        System.out.println("Vertex buffer created ("
                + vertexBuffer.size() + " bytes, device-local via staging).");

        createCommandBuffers();
        createSyncObjects();
    }

    // ------------------------------------------------------------------
    // Command pool + buffers -- record barrier / clear / barrier (dynamic rendering)
    // ------------------------------------------------------------------

    /** The pool command buffers are allocated from, bound to the graphics family. */
    private void createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(device.graphicsFamily());
            // We re-record each frame's buffer every frame, so buffers must be
            // individually resettable. (The earlier record-once model used no
            // flags; it died with the move to per-frame recording.)
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateCommandPool(device.handle(), poolInfo, null, pPool),
                    "Failed to create the command pool");
            commandPool = pPool.get(0);
        }
        System.out.println("Command pool created.");
    }

    /**
     * Allocate one primary command buffer per frame in flight. They start EMPTY:
     * recording happens per frame in {@link #recordCommandBuffer}, because a real
     * frame's contents change every frame and the target image isn't known until
     * acquire. (This replaced the earlier learning model of pre-recording one
     * buffer per swapchain IMAGE once and replaying it.)
     */
    private void createCommandBuffers() {
        commandBuffers = new VkCommandBuffer[MAX_FRAMES_IN_FLIGHT];

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);  // submittable directly
            allocInfo.commandBufferCount(MAX_FRAMES_IN_FLIGHT);

            PointerBuffer pBuffers = stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);
            Vk.check(vkAllocateCommandBuffers(device.handle(), allocInfo, pBuffers),
                    "Failed to allocate command buffers");
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device.handle());
            }
        }
        System.out.println("Allocated " + MAX_FRAMES_IN_FLIGHT + " per-frame command buffers.");
    }

    /**
     * Record one frame's commands into {@code cmd}, targeting the acquired
     * swapchain image: barrier (UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL) ->
     * begin rendering (clear into the image's VIEW) -> end rendering ->
     * barrier (COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR).
     *
     * loadOp=CLEAR (on the rendering attachment) does the actual clearing; with
     * no render pass, the two barriers do by hand what initial/finalLayout used
     * to do invisibly. oldLayout=UNDEFINED means "discard prior contents" --
     * exactly right, since we re-clear the whole image every frame.
     */
    private void recordCommandBuffer(VkCommandBuffer cmd, int imageIndex) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            // The clear color: one VkClearValue for the one color attachment.
            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.get(0).color().float32(stack.floats(clearR, clearG, clearB, 1.0f));

            // One reusable image-memory barrier struct, in the synchronization2
            // spelling: VkImageMemoryBarrier2 carries its OWN src/dst STAGE masks.
            // (Under 1.0 sync the stages were per-CALL arguments that covered every
            // barrier in the call -- sync2 moves them onto each barrier, where they
            // belong.) Retargeted between the two barriers below. The subresource
            // range = the whole color image (1 mip, 1 layer), and we don't transfer
            // queue-family ownership.
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            // vkCmdPipelineBarrier2 takes ONE bundle struct (VkDependencyInfo)
            // pointing at the barrier arrays, replacing 1.0's seven-argument call.
            // We reuse a single bundle for both barriers: it just POINTS at the
            // barrier struct above, which we mutate between the two calls.
            VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack);
            depInfo.sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            depInfo.pImageMemoryBarriers(barrier);

            // The dynamic-rendering color attachment: render INTO the acquired
            // image's view, CLEAR it on load, STORE the result for presentation.
            VkRenderingAttachmentInfo.Buffer colorAttachment =
                    VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
            colorAttachment.imageView(swapchain.imageView(imageIndex));
            colorAttachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAttachment.clearValue(clearValue.get(0));

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType(VK_STRUCTURE_TYPE_RENDERING_INFO);
            renderingInfo.renderArea().offset().x(0).y(0);           // whole image
            renderingInfo.renderArea().extent().width(swapchain.width()).height(swapchain.height());
            renderingInfo.layerCount(1);
            renderingInfo.pColorAttachments(colorAttachment);  // colorAttachmentCount inferred from the buffer

            Vk.check(vkBeginCommandBuffer(cmd, beginInfo),
                    "Failed to begin the command buffer");

            // ---- Barrier 1: make the image renderable (UNDEFINED -> COLOR) ----
            // Gate at COLOR_ATTACHMENT_OUTPUT (the same stage the submit waits on
            // for image acquisition); nothing read before (ACCESS_2_NONE), color
            // writes after.
            barrier.image(swapchain.image(imageIndex));
            barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            barrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            barrier.srcAccessMask(VK_ACCESS_2_NONE);
            barrier.dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            barrier.dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            vkCmdPipelineBarrier2(cmd, depInfo);

            // ---- Render, via dynamic rendering (no render pass / framebuffer) ----
            // loadOp=CLEAR has already filled the image (the orange background)
            // by the time the triangle is drawn over it.
            vkCmdBeginRendering(cmd, renderingInfo);

            // Bind the pipeline: ONE call swaps in the shaders + all the baked
            // fixed-function state.
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

            // Viewport + scissor are DYNAMIC pipeline state (so resize never
            // rebuilds the pipeline) -- set them every frame, here.
            // Viewport = NDC -> pixels mapping; scissor = hard pixel clip.
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f).y(0.0f);
            viewport.width(swapchain.width()).height(swapchain.height());
            viewport.minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().x(0).y(0);
            scissor.extent().width(swapchain.width()).height(swapchain.height());
            vkCmdSetScissor(cmd, 0, scissor);

            // Bind the vertex buffer into BINDING 0 (matching the pipeline's
            // binding description), starting at byte offset 0. Arrays because
            // several bindings can be bound in one call.
            vkCmdBindVertexBuffers(cmd, 0,
                    stack.longs(vertexBuffer.handle()), stack.longs(0));

            // THE draw: 3 vertices, 1 instance, no offsets. The vertex shader's
            // location 0/1 inputs now stream from the bound buffer, sliced per
            // the pipeline's attribute descriptions.
            vkCmdDraw(cmd, 3, 1, 0, 0);

            vkCmdEndRendering(cmd);

            // ---- Barrier 2: make it presentable (COLOR -> PRESENT_SRC) ----
            // dstStage = NONE: nothing INSIDE this command buffer waits on the
            // transition -- presentation is ordered by the renderFinished
            // semaphore instead. (1.0 sync spelled this "BOTTOM_OF_PIPE"; sync2
            // deprecates TOP/BOTTOM_OF_PIPE in favor of the honest NONE.)
            barrier.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            barrier.srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            barrier.dstStageMask(VK_PIPELINE_STAGE_2_NONE);
            barrier.dstAccessMask(VK_ACCESS_2_NONE);
            vkCmdPipelineBarrier2(cmd, depInfo);

            Vk.check(vkEndCommandBuffer(cmd),
                    "Failed to record the command buffer");
        }
    }

    // ------------------------------------------------------------------
    // Synchronization
    // ------------------------------------------------------------------

    /**
     * Create the sync primitives, one set per frame in flight: an imageAvailable
     * SEMAPHORE and a FENCE (GPU->CPU; starts SIGNALED so the first wait doesn't
     * hang). Plus, per swapchain IMAGE, a renderFinished semaphore (see
     * {@link #createRenderFinishedSemaphores}).
     */
    private void createSyncObjects() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack);
            semInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);  // start signaled

            imageAvailableSemaphores = new long[MAX_FRAMES_IN_FLIGHT];
            inFlightFences = new long[MAX_FRAMES_IN_FLIGHT];

            LongBuffer p = stack.longs(VK_NULL_HANDLE);
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                Vk.check(vkCreateSemaphore(device.handle(), semInfo, null, p),
                        "Failed to create imageAvailable semaphore " + i);
                imageAvailableSemaphores[i] = p.get(0);

                Vk.check(vkCreateFence(device.handle(), fenceInfo, null, p),
                        "Failed to create in-flight fence " + i);
                inFlightFences[i] = p.get(0);
            }
        }
        createRenderFinishedSemaphores();
        System.out.println("Sync objects created (" + MAX_FRAMES_IN_FLIGHT + " frames in flight).");
    }

    /**
     * One renderFinished semaphore PER swapchain image, indexed by acquired image
     * (reuse-safe: an image isn't re-acquired until its prior present has consumed
     * the semaphore). Split out from the other sync objects because the IMAGE
     * COUNT can change when the swapchain is recreated -- these rebuild with it.
     */
    private void createRenderFinishedSemaphores() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack);
            semInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer p = stack.longs(VK_NULL_HANDLE);
            renderFinishedSemaphores = new long[swapchain.imageCount()];
            for (int i = 0; i < renderFinishedSemaphores.length; i++) {
                Vk.check(vkCreateSemaphore(device.handle(), semInfo, null, p),
                        "Failed to create renderFinished semaphore " + i);
                renderFinishedSemaphores[i] = p.get(0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-frame draw
    // ------------------------------------------------------------------

    /**
     * Render one frame in slot {@code currentFrame}: wait that slot's fence (it
     * guards the slot's command buffer + semaphore from the frame that used them
     * MAX_FRAMES_IN_FLIGHT ago), acquire an image, re-record and submit the
     * slot's command buffer, present, advance to the next slot.
     * Acquire/present results drive swapchain recreation on resize.
     */
    public void drawFrame() {
        try (MemoryStack stack = stackPush()) {
            // 1. Block until THIS SLOT's previous frame finished. (With an
            //    infinite timeout the only non-SUCCESS results are real errors
            //    like DEVICE_LOST -- so check, don't ignore.)
            Vk.check(vkWaitForFences(device.handle(), stack.longs(inFlightFences[currentFrame]),
                            true, NO_TIMEOUT),
                    "Failed waiting for the in-flight fence");

            // 2. Acquire the next swapchain image (GPU signals imageAvailable when
            //    it's genuinely ready to be drawn into). OUT_OF_DATE = the surface
            //    changed and this swapchain can no longer present to it AT ALL:
            //    rebuild and skip the frame. SUBOPTIMAL = it still works, just not
            //    perfectly -- render this frame, deal with it after present.
            IntBuffer pImageIndex = stack.ints(0);
            int acquired = vkAcquireNextImageKHR(device.handle(), swapchain.handle(), NO_TIMEOUT,
                    imageAvailableSemaphores[currentFrame], VK_NULL_HANDLE, pImageIndex);
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
                // No submit happens, so we also do NOT advance currentFrame --
                // this slot's semaphore went unused and its fence stays signaled;
                // the retry next frame reuses both safely.
                recreateSwapchain();
                return;
            }
            if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
                throw new RuntimeException(
                        "Failed to acquire a swapchain image (VkResult " + acquired + ")");
            }
            int imageIndex = pImageIndex.get(0);

            // Reset the fence ONLY now that we know a submit will follow. The
            // classic deadlock: reset BEFORE acquire, then early-return on
            // OUT_OF_DATE -- the fence is left unsignaled, no submit ever signals
            // it, and the next drawFrame waits on it forever.
            vkResetFences(device.handle(), stack.longs(inFlightFences[currentFrame]));

            // Re-record this slot's command buffer for the image we just got
            // (safe: the fence wait above guarantees the GPU is done with it).
            VkCommandBuffer cmd = commandBuffers[currentFrame];
            Vk.check(vkResetCommandBuffer(cmd, 0), "Failed to reset the command buffer");
            recordCommandBuffer(cmd, imageIndex);

            // 3. Submit that image's command buffer, via synchronization2's
            //    vkQueueSubmit2. Every participant gets its own little info struct,
            //    and each SEMAPHORE carries a PER-SEMAPHORE stage mask -- 1.0's
            //    fragile parallel arrays (pWaitSemaphores + pWaitDstStageMask,
            //    matched by index) are gone, and the counts are inferred.

            // Wait for imageAvailable, but only block the COLOR_ATTACHMENT_OUTPUT
            // stage: work in earlier stages may start before the image is acquired.
            VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            waitInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            waitInfo.semaphore(imageAvailableSemaphores[currentFrame]);
            waitInfo.stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);

            VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmdInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
            cmdInfo.commandBuffer(cmd);

            // Signal renderFinished only once EVERYTHING in the submit completed
            // (ALL_COMMANDS) -- presentation must not start before the final
            // layout transition (barrier 2) has executed.
            VkSemaphoreSubmitInfo.Buffer signalInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            signalInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            signalInfo.semaphore(renderFinishedSemaphores[imageIndex]);
            signalInfo.stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);

            VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2);
            submitInfo.pWaitSemaphoreInfos(waitInfo);
            submitInfo.pCommandBufferInfos(cmdInfo);
            submitInfo.pSignalSemaphoreInfos(signalInfo);

            Vk.check(vkQueueSubmit2(device.graphicsQueue(), submitInfo, inFlightFences[currentFrame]),
                    "Failed to submit the draw command buffer");

            // 4. Present: hand the finished image back to the swapchain to display,
            //    once renderFinished is signaled.
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchain.handle()));
            presentInfo.pImageIndices(pImageIndex);

            int presented = vkQueuePresentKHR(device.presentQueue(), presentInfo);

            // 5. React to "the surface changed". At present-time BOTH out-of-date
            //    and suboptimal trigger a rebuild (the frame already went out; now
            //    is the cheap moment to fix the mismatch). The window's resize
            //    flag is checked too because some drivers keep presenting happily
            //    while the window stretches -- without the flag we'd render
            //    forever at the old size.
            boolean resized = window.consumeFramebufferResized();
            if (presented == VK_ERROR_OUT_OF_DATE_KHR
                    || presented == VK_SUBOPTIMAL_KHR
                    || resized) {
                recreateSwapchain();
            } else {
                Vk.check(presented, "Failed to present the swapchain image");
            }

            // 6. Move to the next frame-in-flight slot (a submit DID happen on
            //    this one, so its fence will signal and free it for reuse).
            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        }
    }

    // ------------------------------------------------------------------
    // Swapchain recreation (resize)
    // ------------------------------------------------------------------

    /**
     * Rebuild everything that depends on the swapchain, against the surface's
     * CURRENT state. The swapchain itself renegotiates extent/format from the
     * live surface; the pre-recorded command buffers bake in image handles,
     * views and the extent, so they must be re-recorded; the per-IMAGE
     * renderFinished semaphores rebuild because the image count can change.
     *
     * Strategy: brute-force-but-correct -- wait for the GPU to go fully idle,
     * destroy, recreate. (The no-stall version hands oldSwapchain to the new
     * chain and retires the old one asynchronously; that seam stays documented
     * in Swapchain for later.)
     */
    private void recreateSwapchain() {
        // A minimized window has a 0x0 framebuffer, and a 0x0 swapchain is
        // illegal -- sleep on events until we're restored to a real size.
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.ints(0);
            IntBuffer h = stack.ints(0);
            window.framebufferSize(w, h);
            while (w.get(0) == 0 || h.get(0) == 0) {
                window.waitEvents();
                window.framebufferSize(w, h);
            }
        }

        waitIdle();

        // Tear down the swapchain-dependent slice, in reverse order...
        destroyRenderFinishedSemaphores();
        int oldFormat = swapchain.imageFormat();
        swapchain.close();

        // ...and rebuild it. Only the per-IMAGE pieces depend on the swapchain
        // now: since command buffers are re-recorded every frame, they pick up
        // the new images/extent automatically -- nothing to rebuild there. The
        // pool and per-frame sync objects survive untouched.
        swapchain = new Swapchain(device, surface, window);
        createRenderFinishedSemaphores();

        // The pipeline baked the attachment FORMAT (not the extent -- viewport
        // is dynamic), and the rebuilt swapchain renegotiated it. Same format
        // (the overwhelmingly common case): keep the pipeline. Changed (e.g.
        // window dragged to a monitor with different surface support): rebake.
        if (swapchain.imageFormat() != oldFormat) {
            pipeline.close();
            pipeline = new Pipeline(device, swapchain.imageFormat(), TRIANGLE_VERT, TRIANGLE_FRAG);
        }

        // Drop any resize flag raised by the burst of events we just handled --
        // otherwise one drag rebuilds the swapchain several times at the same
        // size. (If a REAL resize sneaks in right here, the driver's OUT_OF_DATE
        // on the next acquire/present catches it.)
        window.consumeFramebufferResized();
        System.out.println("Swapchain recreated.");
    }

    private void destroyRenderFinishedSemaphores() {
        for (long s : renderFinishedSemaphores) {
            vkDestroySemaphore(device.handle(), s, null);
        }
    }

    /** Block until the GPU is fully idle -- call before tearing anything down. */
    public void waitIdle() {
        vkDeviceWaitIdle(device.handle());
    }

    // ------------------------------------------------------------------
    // Cleanup -- reverse order of creation.
    // ------------------------------------------------------------------
    public void close() {
        // Sync primitives (per-frame sets + the per-image semaphores).
        if (imageAvailableSemaphores != null) {
            for (long s : imageAvailableSemaphores) {
                vkDestroySemaphore(device.handle(), s, null);
            }
        }
        if (renderFinishedSemaphores != null) {
            destroyRenderFinishedSemaphores();
        }
        if (inFlightFences != null) {
            for (long f : inFlightFences) {
                vkDestroyFence(device.handle(), f, null);
            }
        }
        // Destroying the command pool also frees the command buffers from it.
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device.handle(), commandPool, null);
        }
        if (vertexBuffer != null) {
            vertexBuffer.close();
        }
        pipeline.close();
        // Swapchain.close() destroys our image views, then the swapchain (which
        // frees the images it owns). The swapchain was created FROM the device,
        // so it goes before the device.
        swapchain.close();
        device.close();
    }
}
