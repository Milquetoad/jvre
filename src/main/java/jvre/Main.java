package jvre;

import jvre.core.Device;
import jvre.core.Instance;
import jvre.core.Surface;
import jvre.core.Window;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * jvre bootstrap -- one linear, heavily commented file by design (we get it
 * working top-to-bottom first, then refactor into reusable classes; see the
 * vault). initVulkan() builds the path to the screen one object at a time:
 *
 *   instance -> validation/debug messenger -> surface -> physical device
 *   -> logical device + queues -> swapchain
 *
 * cleanup() tears everything down in reverse (child before parent). This is a
 * learning artifact, so the comments explain the WHY, not just the what.
 */
public class Main {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final CharSequence TITLE = "jvre - clear to color";

    // Bright orange clear color (RGBA in [0,1]). The swapchain is an sRGB format,
    // so these linear values are sRGB-encoded on write -> a vivid orange on screen.
    private static final float CLEAR_R = 1.0f;
    private static final float CLEAR_G = 0.4f;
    private static final float CLEAR_B = 0.0f;

    // Vulkan's "wait forever" timeout sentinel (UINT64_MAX) for fences/acquire.
    private static final long NO_TIMEOUT = 0xFFFFFFFFFFFFFFFFL;

    // Flip to false to build a "release" run with no validation overhead.
    private static final boolean ENABLE_VALIDATION = true;

    private Window window;
    private Instance instance;

    // The window<->Vulkan bridge (VkSurfaceKHR), created from the window + instance.
    private Surface surface;

    // The chosen GPU + our connection to it: physical-device selection, the logical
    // VkDevice, the graphics/present queues, and the queue-family indices. The head
    // of the recreatable "device context".
    private Device device;

    // The swapchain: the queue of images presented to the surface (double/triple
    // buffering). WE create it from the device + surface, and destroy it. The
    // images it holds are OWNED by the swapchain -- not destroyed individually.
    private long swapchain = VK_NULL_HANDLE;
    private long[] swapchainImages;
    // Chosen at creation; every later step (image views, render pass, framebuffers,
    // viewport) needs the format and the extent, so we remember them.
    private int swapchainImageFormat;
    private int swapchainWidth;
    private int swapchainHeight;

    // One VkImageView per swapchain image: the "lens" describing how to interpret
    // that image so the render pass / framebuffer can target it. WE create and
    // destroy these (unlike the images, which the swapchain owns).
    private long[] swapchainImageViews;

    // The render pass: the BLUEPRINT of a rendering operation -- which attachments,
    // what to do with them (here: CLEAR then STORE one color attachment), and the
    // subpasses that use them. Framebuffers and the pipeline are built against it.
    private long renderPass = VK_NULL_HANDLE;

    // One framebuffer per swapchain image: binds that image's VIEW into the render
    // pass's attachment slot(s) at the swapchain size. The concrete target the
    // render pass writes into.
    private long[] swapchainFramebuffers;

    // Command pool: allocator for command buffers, tied to one queue family
    // (graphics). Command buffers: pre-recorded command lists -- one per
    // framebuffer -- that we SUBMIT to a queue. Destroying the pool frees them.
    private long commandPool = VK_NULL_HANDLE;
    private VkCommandBuffer[] commandBuffers;

    // Per-frame synchronization (one frame in flight). Semaphores order GPU<->GPU
    // steps; the fence lets the CPU wait for the GPU before reusing the frame.
    private long imageAvailableSemaphore = VK_NULL_HANDLE;  // image ready to draw into
    // ONE renderFinished semaphore PER swapchain image, indexed by acquired image:
    // a single shared one races with presentation (validation VUID-...-00067).
    private long[] renderFinishedSemaphores;               // render done, safe to present
    private long inFlightFence = VK_NULL_HANDLE;            // GPU finished this frame

    public static void main(String[] args) {
        // See the MemoryStack gotcha note: this machine's GPUs expose enough
        // extensions to overflow the default 64 KB per-thread stack.
        Configuration.STACK_SIZE.set(512);

        new Main().run();
    }

    public void run() {
        initWindow();
        initVulkan();
        mainLoop();
        cleanup();
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------
    private void initWindow() {
        window = new Window(WIDTH, HEIGHT, TITLE);
    }

    // ------------------------------------------------------------------
    // Vulkan
    // ------------------------------------------------------------------
    private void initVulkan() {
        instance = new Instance("jvre demo", ENABLE_VALIDATION);
        surface = new Surface(instance, window);
        device = new Device(instance, surface);
        createSwapchain();
        createImageViews();
        createRenderPass();
        createFramebuffers();
        createCommandPool();
        createCommandBuffers();
        createSyncObjects();
    }

    // ------------------------------------------------------------------
    // Swapchain -- the images we present to the surface
    // ------------------------------------------------------------------

    /**
     * Create the swapchain: negotiate format / present mode / extent / image count
     * with the surface, build the VkSwapchainKHR, and retrieve its image handles.
     * Each choice follows the same shape: QUERY what's supported, then PICK.
     */
    private void createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            // ---- Query (three separate "what does this surface support?" calls) ----
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physicalDevice(), surface.handle(), caps);

            IntBuffer formatCount = stack.ints(0);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice(), surface.handle(), formatCount, null);
            VkSurfaceFormatKHR.Buffer formats =
                    VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice(), surface.handle(), formatCount, formats);

            IntBuffer modeCount = stack.ints(0);
            vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice(), surface.handle(), modeCount, null);
            IntBuffer presentModes = stack.mallocInt(modeCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice(), surface.handle(), modeCount, presentModes);

            // ---- Choose from what's available ----
            VkSurfaceFormatKHR surfaceFormat = chooseSwapSurfaceFormat(formats);
            int presentMode = chooseSwapPresentMode(presentModes);
            VkExtent2D extent = chooseSwapExtent(caps, stack);

            // Request one MORE than the minimum so we're not stuck waiting on the
            // driver to release an image. maxImageCount == 0 means "no maximum".
            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }

            // ---- Build the create-info ----
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(surface.handle());
            createInfo.minImageCount(imageCount);
            createInfo.imageFormat(surfaceFormat.format());
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);  // 1 unless rendering stereoscopic 3D
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);  // we render INTO them

            // If graphics and present are DIFFERENT families, the images are used by
            // both queues. CONCURRENT lets them share without explicit ownership
            // transfers (simpler, slightly slower); EXCLUSIVE is faster but needs
            // manual handoff. Same family -> EXCLUSIVE with nothing to share.
            if (device.graphicsFamily() != device.presentFamily()) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(
                        stack.ints(device.graphicsFamily(), device.presentFamily()));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(caps.currentTransform());          // no rotate/flip
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);  // ignore window alpha
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);                                  // skip obscured pixels
            createInfo.oldSwapchain(VK_NULL_HANDLE);                   // no prior chain to recycle

            LongBuffer pSwapchain = stack.longs(VK_NULL_HANDLE);
            if (vkCreateSwapchainKHR(device.handle(), createInfo, null, pSwapchain) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the swapchain");
            }
            swapchain = pSwapchain.get(0);

            // ---- Retrieve the image handles. The driver may make MORE than we
            // asked for, so query the real count (two-call idiom again). ----
            IntBuffer imgCount = stack.ints(0);
            vkGetSwapchainImagesKHR(device.handle(), swapchain, imgCount, null);
            LongBuffer pImages = stack.mallocLong(imgCount.get(0));
            vkGetSwapchainImagesKHR(device.handle(), swapchain, imgCount, pImages);
            swapchainImages = new long[imgCount.get(0)];
            for (int i = 0; i < swapchainImages.length; i++) {
                swapchainImages[i] = pImages.get(i);
            }

            // ---- Remember what later steps will need ----
            swapchainImageFormat = surfaceFormat.format();
            swapchainWidth = extent.width();
            swapchainHeight = extent.height();

            String pmName = (presentMode == VK_PRESENT_MODE_MAILBOX_KHR) ? "MAILBOX" : "FIFO";
            System.out.println("Swapchain created: " + swapchainImages.length + " images, "
                    + swapchainWidth + "x" + swapchainHeight
                    + ", format " + swapchainImageFormat + ", present mode " + pmName + ".");
        }
    }

    /** Prefer 8-bit BGRA in sRGB color space (correct-looking colors); else take the first. */
    private VkSurfaceFormatKHR chooseSwapSurfaceFormat(VkSurfaceFormatKHR.Buffer available) {
        for (VkSurfaceFormatKHR f : available) {
            if (f.format() == VK_FORMAT_B8G8R8A8_SRGB
                    && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return f;
            }
        }
        return available.get(0);  // any format beats failing
    }

    /** Prefer MAILBOX (low-latency triple buffering); fall back to FIFO (always supported). */
    private int chooseSwapPresentMode(IntBuffer available) {
        for (int i = 0; i < available.capacity(); i++) {
            if (available.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                return VK_PRESENT_MODE_MAILBOX_KHR;
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR;  // guaranteed by the spec
    }

    /**
     * The image resolution, in PIXELS. If the surface pins currentExtent we must
     * use it; otherwise (the sentinel 0xFFFFFFFF) we're free to choose, so we take
     * the window's FRAMEBUFFER size -- pixels, not screen coordinates, which differ
     * on high-DPI displays -- clamped into the surface's allowed min/max.
     */
    private VkExtent2D chooseSwapExtent(VkSurfaceCapabilitiesKHR caps, MemoryStack stack) {
        if (caps.currentExtent().width() != 0xFFFFFFFF) {
            return caps.currentExtent();
        }
        IntBuffer w = stack.ints(0);
        IntBuffer h = stack.ints(0);
        window.framebufferSize(w, h);

        VkExtent2D extent = VkExtent2D.malloc(stack);
        extent.width(clamp(w.get(0),
                caps.minImageExtent().width(), caps.maxImageExtent().width()));
        extent.height(clamp(h.get(0),
                caps.minImageExtent().height(), caps.maxImageExtent().height()));
        return extent;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Image views -- "how to interpret" each swapchain image
    // ------------------------------------------------------------------

    /**
     * Wrap each swapchain image in a VkImageView. The render pass and framebuffers
     * reference VIEWS, not images. For these color images every view is identical
     * apart from the image it wraps: 2D, swapchain format, color aspect, no mips,
     * a single array layer, identity component swizzle.
     */
    private void createImageViews() {
        swapchainImageViews = new long[swapchainImages.length];

        try (MemoryStack stack = stackPush()) {
            LongBuffer pView = stack.mallocLong(1);

            for (int i = 0; i < swapchainImages.length; i++) {
                VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                createInfo.image(swapchainImages[i]);
                createInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
                createInfo.format(swapchainImageFormat);

                // Identity swizzle: R->R, G->G, B->B, A->A (no channel remapping).
                createInfo.components().r(VK_COMPONENT_SWIZZLE_IDENTITY);
                createInfo.components().g(VK_COMPONENT_SWIZZLE_IDENTITY);
                createInfo.components().b(VK_COMPONENT_SWIZZLE_IDENTITY);
                createInfo.components().a(VK_COMPONENT_SWIZZLE_IDENTITY);

                // Which slice of the image this view covers: the COLOR data, mip
                // level 0 only (swapchain images have no mipmaps), one array layer.
                createInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                createInfo.subresourceRange().baseMipLevel(0);
                createInfo.subresourceRange().levelCount(1);
                createInfo.subresourceRange().baseArrayLayer(0);
                createInfo.subresourceRange().layerCount(1);

                if (vkCreateImageView(device.handle(), createInfo, null, pView) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create image view " + i);
                }
                swapchainImageViews[i] = pView.get(0);
            }
        }

        System.out.println("Created " + swapchainImageViews.length + " image views.");
    }

    // ------------------------------------------------------------------
    // Render pass -- the blueprint that (with loadOp=CLEAR) clears the screen
    // ------------------------------------------------------------------

    /**
     * Describe a one-subpass render pass with a single color attachment that we
     * CLEAR at the start and STORE at the end, leaving it ready to present. This
     * object defines the clear; the command buffer (later) records it and the loop
     * runs it. The subpass dependency is forward-looking sync for the render loop.
     */
    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            // ---- One color attachment: a swapchain image we clear, then present ----
            VkAttachmentDescription.Buffer colorAttachment =
                    VkAttachmentDescription.calloc(1, stack);
            colorAttachment.format(swapchainImageFormat);
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT);            // no multisampling
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);       // <-- clears the screen
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);     // keep result (to present)
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);   // no stencil
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);         // don't care about prior contents
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);     // leave it ready to present

            // ---- The subpass uses that attachment as its color target ----
            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.attachment(0);  // index into pAttachments below
            colorRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);  // layout DURING the subpass

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            subpass.colorAttachmentCount(1);
            subpass.pColorAttachments(colorRef);
            // The index here (0) is what a fragment shader writes as layout(location=0).

            // ---- Dependency: make the implicit pre-subpass wait for the color
            // stage before we write. Inert now; makes the render loop correct. ----
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.srcSubpass(VK_SUBPASS_EXTERNAL);  // the implicit "before"
            dependency.dstSubpass(0);                    // our subpass
            dependency.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependency.srcAccessMask(0);
            dependency.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependency.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            renderPassInfo.pAttachments(colorAttachment);
            renderPassInfo.pSubpasses(subpass);
            renderPassInfo.pDependencies(dependency);

            LongBuffer pRenderPass = stack.longs(VK_NULL_HANDLE);
            if (vkCreateRenderPass(device.handle(), renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the render pass");
            }
            renderPass = pRenderPass.get(0);
        }
        System.out.println("Render pass created.");
    }

    // ------------------------------------------------------------------
    // Framebuffers -- bind the image views into the render pass
    // ------------------------------------------------------------------

    /**
     * One framebuffer per swapchain image: it plugs that image's view into the
     * render pass's attachment slot(s), at the swapchain size. All share the same
     * render pass; they differ only in which image view they wrap.
     */
    private void createFramebuffers() {
        swapchainFramebuffers = new long[swapchainImageViews.length];

        try (MemoryStack stack = stackPush()) {
            LongBuffer attachments = stack.mallocLong(1);  // one attachment (color) per framebuffer
            LongBuffer pFramebuffer = stack.mallocLong(1);

            for (int i = 0; i < swapchainImageViews.length; i++) {
                attachments.put(0, swapchainImageViews[i]);

                VkFramebufferCreateInfo createInfo = VkFramebufferCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                createInfo.renderPass(renderPass);          // must be compatible with this pass
                createInfo.pAttachments(attachments);       // the view filling slot 0
                createInfo.width(swapchainWidth);
                createInfo.height(swapchainHeight);
                createInfo.layers(1);

                if (vkCreateFramebuffer(device.handle(), createInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer " + i);
                }
                swapchainFramebuffers[i] = pFramebuffer.get(0);
            }
        }

        System.out.println("Created " + swapchainFramebuffers.length + " framebuffers.");
    }

    // ------------------------------------------------------------------
    // Command pool + buffers -- record "begin render pass (clear) / end"
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
            if (vkCreateCommandPool(device.handle(), poolInfo, null, pPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the command pool");
            }
            commandPool = pPool.get(0);
        }
        System.out.println("Command pool created.");
    }

    /**
     * Allocate one primary command buffer per framebuffer and pre-record the clear
     * into each: begin -> begin render pass (with the ORANGE clear value, pointed
     * at that framebuffer) -> end render pass -> end. The render pass's
     * loadOp=CLEAR does the actual clearing, so there's nothing in between.
     */
    private void createCommandBuffers() {
        int count = swapchainFramebuffers.length;
        commandBuffers = new VkCommandBuffer[count];

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);  // submittable directly
            allocInfo.commandBufferCount(count);

            PointerBuffer pBuffers = stack.mallocPointer(count);
            if (vkAllocateCommandBuffers(device.handle(), allocInfo, pBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers");
            }
            for (int i = 0; i < count; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device.handle());
            }

            // The clear color: BRIGHT ORANGE. One VkClearValue for the one color
            // attachment (index matches the render pass's attachment 0).
            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
            clearValues.get(0).color().float32(stack.floats(CLEAR_R, CLEAR_G, CLEAR_B, 1.0f));

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            renderPassInfo.renderPass(renderPass);
            renderPassInfo.pClearValues(clearValues);
            renderPassInfo.renderArea().offset().x(0).y(0);           // whole image
            renderPassInfo.renderArea().extent().width(swapchainWidth).height(swapchainHeight);

            for (int i = 0; i < count; i++) {
                VkCommandBuffer cmd = commandBuffers[i];

                if (vkBeginCommandBuffer(cmd, beginInfo) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to begin command buffer " + i);
                }

                renderPassInfo.framebuffer(swapchainFramebuffers[i]);  // this image's target
                // INLINE = the commands live in this primary buffer (no secondary buffers).
                vkCmdBeginRenderPass(cmd, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
                // (loadOp=CLEAR fills the image; nothing else to record for clear-to-color.)
                vkCmdEndRenderPass(cmd);

                if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to record command buffer " + i);
                }
            }
        }
        System.out.println("Recorded " + count + " command buffers (clear to orange).");
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
            if (vkCreateSemaphore(device.handle(), semInfo, null, p) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create imageAvailable semaphore");
            }
            imageAvailableSemaphore = p.get(0);

            // One renderFinished semaphore per swapchain image (reuse-safe: an image
            // isn't re-acquired until its prior present has consumed the semaphore).
            renderFinishedSemaphores = new long[swapchainImages.length];
            for (int i = 0; i < renderFinishedSemaphores.length; i++) {
                if (vkCreateSemaphore(device.handle(), semInfo, null, p) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create renderFinished semaphore " + i);
                }
                renderFinishedSemaphores[i] = p.get(0);
            }

            if (vkCreateFence(device.handle(), fenceInfo, null, p) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create in-flight fence");
            }
            inFlightFence = p.get(0);
        }
        System.out.println("Sync objects created.");
    }

    // ------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------
    private void mainLoop() {
        System.out.println("Entering render loop -- clearing to orange. Close the window to exit.");
        while (!window.shouldClose()) {
            window.pollEvents();
            drawFrame();
        }
        // Let the GPU finish the in-flight frame before cleanup() frees anything.
        vkDeviceWaitIdle(device.handle());
    }

    /**
     * Render one frame: wait the previous frame's fence, acquire an image, submit
     * its pre-recorded command buffer (wait on imageAvailable, signal
     * renderFinished + the fence), then present (wait on renderFinished).
     */
    private void drawFrame() {
        try (MemoryStack stack = stackPush()) {
            // 1. Block until the previous frame finished, then reset the fence.
            vkWaitForFences(device.handle(), stack.longs(inFlightFence), true, NO_TIMEOUT);
            vkResetFences(device.handle(), stack.longs(inFlightFence));

            // 2. Acquire the next swapchain image (GPU signals imageAvailable when
            //    it's genuinely ready to be drawn into).
            IntBuffer pImageIndex = stack.ints(0);
            vkAcquireNextImageKHR(device.handle(), swapchain, NO_TIMEOUT,
                    imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
            int imageIndex = pImageIndex.get(0);

            // 3. Submit that image's command buffer. Wait on imageAvailable at the
            //    COLOR_ATTACHMENT_OUTPUT stage; signal renderFinished + the fence.
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stack.longs(imageAvailableSemaphore));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            submitInfo.pCommandBuffers(stack.pointers(commandBuffers[imageIndex]));
            submitInfo.pSignalSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]));

            if (vkQueueSubmit(device.graphicsQueue(), submitInfo, inFlightFence) != VK_SUCCESS) {
                throw new RuntimeException("Failed to submit the draw command buffer");
            }

            // 4. Present: hand the finished image back to the swapchain to display,
            //    once renderFinished is signaled. (We ignore out-of-date/suboptimal
            //    results -- the window is fixed size, so no swapchain recreation yet.)
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchain));
            presentInfo.pImageIndices(pImageIndex);

            vkQueuePresentKHR(device.presentQueue(), presentInfo);
        }
    }

    // ------------------------------------------------------------------
    // Cleanup — reverse order of creation.
    // ------------------------------------------------------------------
    private void cleanup() {
        // Reverse order of creation. The swapchain is created FROM the device, so
        // it goes before the device; the device (everything device-level hangs off
        // it) goes before the instance-level objects. (Destroying the swapchain also
        // frees the images it owns -- we don't destroy those individually.)
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
        // Framebuffers reference the image views + render pass, so they go first.
        if (swapchainFramebuffers != null) {
            for (long fb : swapchainFramebuffers) {
                vkDestroyFramebuffer(device.handle(), fb, null);
            }
        }
        // Render pass is built against the swapchain's format; tear it down before
        // the views/swapchain it describes.
        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device.handle(), renderPass, null);
        }
        // Image views reference the swapchain's images, so destroy them first.
        if (swapchainImageViews != null) {
            for (long view : swapchainImageViews) {
                vkDestroyImageView(device.handle(), view, null);
            }
        }
        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device.handle(), swapchain, null);
        }
        if (device != null) {
            device.close();
        }
        // Surface is owned by the instance -> destroy it before the instance.
        if (surface != null) {
            surface.close();
        }
        // Instance.close() destroys the debug messenger, then the instance, then
        // frees the native callback.
        instance.close();

        window.close();

        System.out.println("Cleaned up. Bye.");
    }
}
