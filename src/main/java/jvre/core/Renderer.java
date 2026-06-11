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
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

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

    private final Device device;
    private Swapchain swapchain;

    // The clear color (RGBA in [0,1]); becomes real API once there is more to
    // render than a clear. The swapchain is an sRGB format, so these linear
    // values are sRGB-encoded on write.
    private final float clearR;
    private final float clearG;
    private final float clearB;

    // Command pool: allocator for command buffers, tied to one queue family
    // (graphics). Command buffers: pre-recorded command lists -- one per
    // swapchain image -- that we SUBMIT to a queue. Destroying the pool frees them.
    private long commandPool = VK_NULL_HANDLE;
    private VkCommandBuffer[] commandBuffers;

    // Per-frame synchronization (one frame in flight). Semaphores order GPU<->GPU
    // steps; the fence lets the CPU wait for the GPU before reusing the frame.
    private long imageAvailableSemaphore = VK_NULL_HANDLE;  // image ready to draw into
    // ONE renderFinished semaphore PER swapchain image, indexed by acquired image:
    // a single shared one races with presentation (validation VUID-...-00067).
    private long[] renderFinishedSemaphores;               // render done, safe to present
    private long inFlightFence = VK_NULL_HANDLE;            // GPU finished this frame

    public Renderer(Instance instance, Surface surface, Window window,
                    float clearR, float clearG, float clearB) {
        this.surface = surface;
        this.window = window;
        this.clearR = clearR;
        this.clearG = clearG;
        this.clearB = clearB;

        // The device context, top to bottom.
        this.device = new Device(instance, surface);
        this.swapchain = new Swapchain(device, surface, window);
        createCommandPool();
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
            // No flags: we record each buffer ONCE and never reset it. A loop that
            // re-records every frame would use RESET_COMMAND_BUFFER_BIT instead.

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateCommandPool(device.handle(), poolInfo, null, pPool),
                    "Failed to create the command pool");
            commandPool = pPool.get(0);
        }
        System.out.println("Command pool created.");
    }

    /**
     * Allocate one primary command buffer per swapchain image and pre-record the
     * clear into each. With NO render pass, WE drive the image's layout transitions
     * by hand, so each buffer is: begin -> barrier (UNDEFINED -> COLOR_ATTACHMENT
     * _OPTIMAL) -> begin rendering (clear into the image's VIEW) -> end
     * rendering -> barrier (COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR) -> end.
     *
     * loadOp=CLEAR (on the rendering attachment) still does the actual clearing.
     * The two barriers are what the render pass used to do invisibly via
     * initial/finalLayout. oldLayout=UNDEFINED means "discard prior contents",
     * which is exactly right every frame since we re-clear -- so it's safe to
     * record these ONCE and replay them.
     */
    private void createCommandBuffers() {
        int count = swapchain.imageCount();
        commandBuffers = new VkCommandBuffer[count];

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);  // submittable directly
            allocInfo.commandBufferCount(count);

            PointerBuffer pBuffers = stack.mallocPointer(count);
            Vk.check(vkAllocateCommandBuffers(device.handle(), allocInfo, pBuffers),
                    "Failed to allocate command buffers");
            for (int i = 0; i < count; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device.handle());
            }

            // The clear color: one VkClearValue for the one color attachment in
            // the rendering info below.
            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.get(0).color().float32(stack.floats(clearR, clearG, clearB, 1.0f));

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            // One reusable image-memory barrier struct, in the synchronization2
            // spelling: VkImageMemoryBarrier2 carries its OWN src/dst STAGE masks.
            // (Under 1.0 sync the stages were per-CALL arguments that covered every
            // barrier in the call -- sync2 moves them onto each barrier, where they
            // belong.) We retarget image/layouts/stages per buffer. The subresource
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

            // The dynamic-rendering color attachment: render INTO this image's view,
            // CLEAR it on load, STORE the result for presentation. We point
            // .imageView() at the right view per buffer below.
            VkRenderingAttachmentInfo.Buffer colorAttachment =
                    VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
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

            for (int i = 0; i < count; i++) {
                VkCommandBuffer cmd = commandBuffers[i];

                Vk.check(vkBeginCommandBuffer(cmd, beginInfo),
                        "Failed to begin command buffer " + i);

                // ---- Barrier 1: make the image renderable (UNDEFINED -> COLOR) ----
                // Gate at COLOR_ATTACHMENT_OUTPUT (the same stage the submit waits on
                // for image acquisition); nothing read before (ACCESS_2_NONE), color
                // writes after.
                barrier.image(swapchain.image(i));
                barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                barrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
                barrier.srcAccessMask(VK_ACCESS_2_NONE);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
                barrier.dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                vkCmdPipelineBarrier2(cmd, depInfo);

                // ---- Clear, via dynamic rendering (no render pass / framebuffer) ----
                colorAttachment.imageView(swapchain.imageView(i));  // this image's target
                vkCmdBeginRendering(cmd, renderingInfo);
                // (loadOp=CLEAR fills the image; nothing else to draw for clear-to-color.)
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
                        "Failed to record command buffer " + i);
            }
        }
        System.out.println("Recorded " + count + " command buffers (clear, dynamic rendering).");
    }

    // ------------------------------------------------------------------
    // Synchronization
    // ------------------------------------------------------------------

    /**
     * Create the sync primitives: an imageAvailable SEMAPHORE, one renderFinished
     * SEMAPHORE PER swapchain image (a single shared one races with presentation --
     * VUID-vkQueueSubmit-pSignalSemaphores-00067), and one FENCE (GPU->CPU) for the
     * single in-flight frame. The fence starts SIGNALED so the first wait doesn't hang.
     */
    private void createSyncObjects() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack);
            semInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);  // start signaled

            LongBuffer p = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateSemaphore(device.handle(), semInfo, null, p),
                    "Failed to create imageAvailable semaphore");
            imageAvailableSemaphore = p.get(0);

            // One renderFinished semaphore per swapchain image (reuse-safe: an image
            // isn't re-acquired until its prior present has consumed the semaphore).
            renderFinishedSemaphores = new long[swapchain.imageCount()];
            for (int i = 0; i < renderFinishedSemaphores.length; i++) {
                Vk.check(vkCreateSemaphore(device.handle(), semInfo, null, p),
                        "Failed to create renderFinished semaphore " + i);
                renderFinishedSemaphores[i] = p.get(0);
            }

            Vk.check(vkCreateFence(device.handle(), fenceInfo, null, p),
                    "Failed to create in-flight fence");
            inFlightFence = p.get(0);
        }
        System.out.println("Sync objects created.");
    }

    // ------------------------------------------------------------------
    // Per-frame draw
    // ------------------------------------------------------------------

    /**
     * Render one frame: wait the previous frame's fence, acquire an image, submit
     * its pre-recorded command buffer (wait on imageAvailable, signal
     * renderFinished + the fence), then present (wait on renderFinished).
     */
    public void drawFrame() {
        try (MemoryStack stack = stackPush()) {
            // 1. Block until the previous frame finished, then reset the fence.
            //    (With an infinite timeout the only non-SUCCESS results are real
            //    errors like DEVICE_LOST -- so check, don't ignore.)
            Vk.check(vkWaitForFences(device.handle(), stack.longs(inFlightFence), true, NO_TIMEOUT),
                    "Failed waiting for the in-flight fence");
            vkResetFences(device.handle(), stack.longs(inFlightFence));

            // 2. Acquire the next swapchain image (GPU signals imageAvailable when
            //    it's genuinely ready to be drawn into).
            IntBuffer pImageIndex = stack.ints(0);
            vkAcquireNextImageKHR(device.handle(), swapchain.handle(), NO_TIMEOUT,
                    imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
            int imageIndex = pImageIndex.get(0);

            // 3. Submit that image's command buffer, via synchronization2's
            //    vkQueueSubmit2. Every participant gets its own little info struct,
            //    and each SEMAPHORE carries a PER-SEMAPHORE stage mask -- 1.0's
            //    fragile parallel arrays (pWaitSemaphores + pWaitDstStageMask,
            //    matched by index) are gone, and the counts are inferred.

            // Wait for imageAvailable, but only block the COLOR_ATTACHMENT_OUTPUT
            // stage: work in earlier stages may start before the image is acquired.
            VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            waitInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            waitInfo.semaphore(imageAvailableSemaphore);
            waitInfo.stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);

            VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmdInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
            cmdInfo.commandBuffer(commandBuffers[imageIndex]);

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

            Vk.check(vkQueueSubmit2(device.graphicsQueue(), submitInfo, inFlightFence),
                    "Failed to submit the draw command buffer");

            // 4. Present: hand the finished image back to the swapchain to display,
            //    once renderFinished is signaled. (We ignore out-of-date/suboptimal
            //    results -- the window is fixed size, so no swapchain recreation yet.)
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchain.handle()));
            presentInfo.pImageIndices(pImageIndex);

            vkQueuePresentKHR(device.presentQueue(), presentInfo);
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
        // Per-frame sync primitives.
        if (imageAvailableSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device.handle(), imageAvailableSemaphore, null);
        }
        if (renderFinishedSemaphores != null) {
            for (long s : renderFinishedSemaphores) {
                vkDestroySemaphore(device.handle(), s, null);
            }
        }
        if (inFlightFence != VK_NULL_HANDLE) {
            vkDestroyFence(device.handle(), inFlightFence, null);
        }
        // Destroying the command pool also frees the command buffers from it.
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device.handle(), commandPool, null);
        }
        // Swapchain.close() destroys our image views, then the swapchain (which
        // frees the images it owns). The swapchain was created FROM the device,
        // so it goes before the device.
        swapchain.close();
        device.close();
    }
}
