package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The swapchain plus its per-image views: the rotating set of images we render
 * into and hand to the surface for display (double/triple buffering), and one
 * VkImageView per image describing how to read/write it.
 *
 * Part of the recreatable "device context" -- format and extent are negotiated
 * HERE against the live surface, so on a resize you tear this down and build a
 * fresh one. Created FROM the device + surface. Ownership split: the IMAGES
 * belong to the swapchain (freed when it is destroyed -- we never destroy them
 * individually), but the image VIEWS are ours to create and destroy.
 *
 * Each negotiation follows the same shape: QUERY what the surface supports, then
 * PICK from it (preferred option, else a guaranteed fallback).
 */
public class Swapchain {

    private final Device device;  // kept so we can create views from / destroy ourselves on it

    private final long handle;
    private final long[] images;      // OWNED by the swapchain; not destroyed individually
    private final long[] imageViews;  // ours: created and destroyed here
    private final int imageFormat;    // remembered: render pass / dynamic rendering need it
    private final int width;
    private final int height;

    // Depth buffer -- sized to the swapchain extent, so it lives and dies WITH the
    // swapchain (recreated together on resize). Non-final: set in a helper, not
    // directly in the constructor body.
    private int depthFormat;
    private long depthImage;
    private long depthAllocation;  // VmaAllocation
    private long depthView;

    // MSAA: the multisampled color target the scene actually renders into; the
    // swapchain image demotes to RESOLVE destination. Same lifetime story as the
    // depth buffer (extent-sized, recreated with the chain). The sample count is
    // QUERIED, not assumed -- a device fact like the depth format.
    private int msaaSamples;       // VK_SAMPLE_COUNT_n_BIT (the bit value == n)
    private long msaaColorImage;
    private long msaaColorAllocation;  // VmaAllocation
    private long msaaColorView;

    public Swapchain(Device device, Surface surface, Window window, boolean vsync) {
        this.device = device;

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
            VkSurfaceFormatKHR surfaceFormat = chooseSurfaceFormat(formats);
            int presentMode = choosePresentMode(presentModes, vsync);
            VkExtent2D extent = chooseExtent(caps, window, stack);

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
            // This branch is logged below as a FINGERPRINT line: the CONCURRENT
            // path has never run on the author's hardware (the 4090 shares
            // families -> EXCLUSIVE), so a split-family GPU reporting "CONCURRENT"
            // tells us instantly it's standing in the never-tested branch.
            String sharing;
            if (device.graphicsFamily() != device.presentFamily()) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(
                        stack.ints(device.graphicsFamily(), device.presentFamily()));
                sharing = "CONCURRENT (graphics=" + device.graphicsFamily()
                        + ", present=" + device.presentFamily() + ")";
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
                sharing = "EXCLUSIVE (graphics=present=" + device.graphicsFamily() + ")";
            }

            createInfo.preTransform(caps.currentTransform());          // no rotate/flip
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);  // ignore window alpha
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);                                  // skip obscured pixels
            createInfo.oldSwapchain(VK_NULL_HANDLE);                   // no prior chain to recycle

            LongBuffer pSwapchain = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateSwapchainKHR(device.handle(), createInfo, null, pSwapchain),
                    "Failed to create the swapchain");
            handle = pSwapchain.get(0);

            // ---- Retrieve the image handles. The driver may make MORE than we
            // asked for, so query the real count (two-call idiom again). ----
            IntBuffer imgCount = stack.ints(0);
            vkGetSwapchainImagesKHR(device.handle(), handle, imgCount, null);
            LongBuffer pImages = stack.mallocLong(imgCount.get(0));
            vkGetSwapchainImagesKHR(device.handle(), handle, imgCount, pImages);
            images = new long[imgCount.get(0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = pImages.get(i);
            }

            // ---- Remember what later steps will need ----
            imageFormat = surfaceFormat.format();
            width = extent.width();
            height = extent.height();

            String pmName = (presentMode == VK_PRESENT_MODE_MAILBOX_KHR) ? "MAILBOX" : "FIFO";
            System.out.println("Swapchain created: " + images.length + " images, "
                    + width + "x" + height
                    + ", format " + imageFormat + ", present mode " + pmName
                    + ", sharing " + sharing + ".");
        }

        imageViews = createImageViews();
        msaaSamples = chooseSampleCount();
        createColorResources();
        createDepthResources();
    }

    public long handle()         { return handle; }
    public int imageFormat()     { return imageFormat; }
    public int width()           { return width; }
    public int height()          { return height; }
    public int imageCount()      { return images.length; }
    public long image(int i)     { return images[i]; }      // for pipeline barriers (layout transitions)
    public long imageView(int i) { return imageViews[i]; }  // for rendering (color attachment target)
    public int depthFormat()     { return depthFormat; }    // baked into the Pipeline
    public long depthView()      { return depthView; }      // depth attachment target (dynamic rendering)
    public long depthImage()     { return depthImage; }     // for the layout-transition barrier
    public int sampleCount()     { return msaaSamples; }    // baked into the Pipeline (rasterizationSamples)
    public long msaaColorView()  { return msaaColorView; }  // the color attachment the scene renders into
    public long msaaColorImage() { return msaaColorImage; } // for the layout-transition barrier

    /** Destroy depth resources + our image views, then the swapchain (which frees its images). */
    public void close() {
        vkDestroyImageView(device.handle(), depthView, null);
        vmaDestroyImage(device.allocator(), depthImage, depthAllocation);
        vkDestroyImageView(device.handle(), msaaColorView, null);
        vmaDestroyImage(device.allocator(), msaaColorImage, msaaColorAllocation);
        for (long view : imageViews) {
            vkDestroyImageView(device.handle(), view, null);
        }
        vkDestroySwapchainKHR(device.handle(), handle, null);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Wrap each swapchain image in a VkImageView -- the "lens" the render pass /
     * dynamic rendering targets. For these color images every view is identical
     * apart from the image it wraps: 2D, swapchain format, color aspect, no mips,
     * a single array layer, identity component swizzle.
     */
    private long[] createImageViews() {
        long[] views = new long[images.length];

        try (MemoryStack stack = stackPush()) {
            LongBuffer pView = stack.mallocLong(1);

            for (int i = 0; i < images.length; i++) {
                VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                createInfo.image(images[i]);
                createInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
                createInfo.format(imageFormat);

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

                Vk.check(vkCreateImageView(device.handle(), createInfo, null, pView),
                        "Failed to create image view " + i);
                views[i] = pView.get(0);
            }
        }

        System.out.println("Created " + views.length + " image views.");
        return views;
    }

    /**
     * Pick the MSAA sample count: the highest power of two <= 4 that BOTH the
     * color and depth framebuffer paths support (the device advertises each as a
     * bitmask of VK_SAMPLE_COUNT bits, where the bit value IS the count -- AND
     * them for the usable set). 4x is the sweet spot: visually most of the win
     * of 8x at half the bandwidth, and universally supported on desktop -- but
     * we still QUERY rather than assume, like the depth format.
     */
    private int chooseSampleCount() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.physicalDevice(), props);
            int counts = props.limits().framebufferColorSampleCounts()
                    & props.limits().framebufferDepthSampleCounts();
            if ((counts & VK_SAMPLE_COUNT_4_BIT) != 0) return VK_SAMPLE_COUNT_4_BIT;
            if ((counts & VK_SAMPLE_COUNT_2_BIT) != 0) return VK_SAMPLE_COUNT_2_BIT;
            return VK_SAMPLE_COUNT_1_BIT;  // MSAA effectively off
        }
    }

    /**
     * Create the multisampled color target: the image the scene RENDERS INTO
     * (the swapchain image becomes the resolve destination). Swapchain format
     * (the pipeline's color format must match what it renders into), the chosen
     * sample count, COLOR_ATTACHMENT usage. Like the depth buffer: a full-screen
     * attachment -> DEDICATED_MEMORY, recreated with the chain, and SHARED
     * across frames in flight (the per-frame barrier in the Renderer syncs
     * against the previous frame's use, same as depth).
     */
    private void createColorResources() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.format(imageFormat);
            imageInfo.extent().width(width).height(height).depth(1);
            imageInfo.mipLevels(1);            // multisampled images never have mips (spec rule)
            imageInfo.arrayLayers(1);
            imageInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            imageInfo.samples(msaaSamples);    // the point of this image
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(VMA_MEMORY_USAGE_AUTO);
            allocInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(device.allocator(), imageInfo, allocInfo,
                            pImage, pAllocation, null),
                    "Failed to create the MSAA color image");
            msaaColorImage = pImage.get(0);
            msaaColorAllocation = pAllocation.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(msaaColorImage);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(imageFormat);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(1);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateImageView(device.handle(), viewInfo, null, pView),
                    "Failed to create the MSAA color image view");
            msaaColorView = pView.get(0);
        }
        System.out.println("MSAA color target created (" + width + "x" + height
                + ", " + msaaSamples + "x samples).");
    }

    /**
     * Create the depth buffer: one VkImage at the swapchain extent (DEVICE_LOCAL,
     * DEPTH_STENCIL_ATTACHMENT usage) plus a DEPTH-aspect view. Same image+memory
     * shape as a Texture, minus the upload and sampler -- it's a render target the
     * GPU writes, never CPU data. Cleared and reused every frame; recreated with
     * the swapchain (so it always matches the color images' size).
     */
    private void createDepthResources() {
        depthFormat = findDepthFormat();

        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.format(depthFormat);
            imageInfo.extent().width(width).height(height).depth(1);
            imageInfo.mipLevels(1);
            imageInfo.arrayLayers(1);
            imageInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            // MUST match the color target's sample count -- depth testing happens
            // per SAMPLE, so a 1x depth buffer against a 4x color target is
            // meaningless (and illegal).
            imageInfo.samples(msaaSamples);
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            // VMA-backed, GPU-only (AUTO, no host access). One extra hint:
            // DEDICATED_MEMORY -- full-screen attachments (depth, later MSAA
            // color) are the textbook case for a dedicated allocation rather
            // than a slot in a shared block: large, long-lived, recreated on
            // resize, and drivers can optimize dedicated attachment memory.
            // VMA exposes the choice as a flag; small textures stay pooled.
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(VMA_MEMORY_USAGE_AUTO);
            allocInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(device.allocator(), imageInfo, allocInfo,
                            pImage, pAllocation, null),
                    "Failed to create the depth image");
            depthImage = pImage.get(0);
            depthAllocation = pAllocation.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(depthImage);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(depthFormat);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);  // DEPTH, not COLOR
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(1);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateImageView(device.handle(), viewInfo, null, pView),
                    "Failed to create the depth image view");
            depthView = pView.get(0);
        }
        System.out.println("Depth buffer created (" + width + "x" + height
                + ", format " + depthFormat + ").");
    }

    /**
     * Pick a depth format the GPU supports as a DEPTH_STENCIL_ATTACHMENT with
     * OPTIMAL tiling. D32_SFLOAT (depth only, no stencil) is preferred and is
     * effectively universal on desktop; the stencil-bearing formats are
     * fallbacks. We query rather than assume -- format support is a device fact.
     */
    private int findDepthFormat() {
        int[] candidates = {
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK_FORMAT_D24_UNORM_S8_UINT,
        };
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            for (int fmt : candidates) {
                vkGetPhysicalDeviceFormatProperties(device.physicalDevice(), fmt, props);
                if ((props.optimalTilingFeatures()
                        & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return fmt;
                }
            }
        }
        throw new RuntimeException("No supported depth-stencil attachment format found");
    }

    /** Prefer 8-bit BGRA in sRGB color space (correct-looking colors); else take the first. */
    private VkSurfaceFormatKHR chooseSurfaceFormat(VkSurfaceFormatKHR.Buffer available) {
        for (VkSurfaceFormatKHR f : available) {
            if (f.format() == VK_FORMAT_B8G8R8A8_SRGB
                    && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return f;
            }
        }
        return available.get(0);  // any format beats failing
    }

    /**
     * Vsync ON -> FIFO (the spec-guaranteed, refresh-synced, no-tearing mode).
     * Vsync OFF -> prefer MAILBOX (low-latency triple buffering, uncapped), else
     * fall back to FIFO. The caller picks via {@link RendererOptions#vsync}.
     */
    private int choosePresentMode(IntBuffer available, boolean vsync) {
        if (!vsync) {
            for (int i = 0; i < available.capacity(); i++) {
                if (available.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                    return VK_PRESENT_MODE_MAILBOX_KHR;
                }
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
    private VkExtent2D chooseExtent(VkSurfaceCapabilitiesKHR caps, Window window, MemoryStack stack) {
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
}
