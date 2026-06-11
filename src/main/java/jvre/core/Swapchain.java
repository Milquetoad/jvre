package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
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

    public Swapchain(Device device, Surface surface, Window window) {
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
            int presentMode = choosePresentMode(presentModes);
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
                    + ", format " + imageFormat + ", present mode " + pmName + ".");
        }

        imageViews = createImageViews();
    }

    public long handle()         { return handle; }
    public int imageFormat()     { return imageFormat; }
    public int width()           { return width; }
    public int height()          { return height; }
    public int imageCount()      { return images.length; }
    public long image(int i)     { return images[i]; }      // for pipeline barriers (layout transitions)
    public long imageView(int i) { return imageViews[i]; }  // for rendering (color attachment target)

    /** Destroy our image views, then the swapchain (which frees the images it owns). */
    public void close() {
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

    /** Prefer MAILBOX (low-latency triple buffering); fall back to FIFO (always supported). */
    private int choosePresentMode(IntBuffer available) {
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
