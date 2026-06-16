package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * An OFFSCREEN render destination -- "a {@link Texture} you render INTO instead of
 * uploading pixels into." The foundation of render-to-texture (Roadmap 4a) and the
 * gateway to post-processing, pixel-perfect upscaling, minimaps and shadow maps.
 *
 * <h2>Why this is just the swapchain, minus the surface</h2>
 * Everything we draw into is a color-attachment {@code VkImage}. The swapchain's
 * images are special only in that the SURFACE owns them and we PRESENT them; an
 * offscreen target is the same kind of image, except WE create it (via VMA, like
 * the depth buffer) and it ends each pass in {@code SHADER_READ_ONLY_OPTIMAL} --
 * ready to be sampled -- rather than {@code PRESENT_SRC_KHR}.
 *
 * <h2>Renders exactly like the screen</h2>
 * jvre's pipelines BAKE the color format, depth format and sample count. So a
 * target reuses every existing pipeline (L2 shapes, the cube scene, an effect)
 * for FREE by matching all three to the swapchain's. The deliberate consequence:
 * a target inherits the renderer's MSAA. When multisampled it carries the SAME
 * three-image shape the swapchain does --
 * <ul>
 *   <li><b>color</b> (single-sample, {@code COLOR_ATTACHMENT | SAMPLED}): the
 *       RESOLVE destination, and the image shaders later sample. This is the
 *       {@link #texture()} you read back.</li>
 *   <li><b>msaaColor</b> (N-sample, {@code COLOR_ATTACHMENT}): what the scene
 *       actually renders into; resolved (averaged) into {@code color} at
 *       end-of-rendering. Absent when MSAA is off -- then the scene renders
 *       straight into {@code color}.</li>
 *   <li><b>depth</b> (N-sample): per-sample depth test, matching the color
 *       target's sample count (a 1x depth buffer against an Nx color target is
 *       illegal).</li>
 * </ul>
 * Off (1x): just {@code color} + {@code depth}, no resolve.
 *
 * <p>Caller-owned (like {@link Texture} and {@link Pipeline}): {@code close()} it
 * before the Renderer. The full-screen attachments use VMA DEDICATED_MEMORY, the
 * same call the depth/MSAA targets in {@link Swapchain} make.
 */
public class RenderTarget {

    private final Device device;
    private final int width;
    private final int height;
    private final int sampleCount;   // VK_SAMPLE_COUNT_n_BIT (n == the bit value)

    // The sampleable surface: single-sample COLOR_ATTACHMENT|SAMPLED. Owns its own
    // view + sampler -- it IS a Texture, so it samples through the existing paths.
    private final Texture color;

    // The multisampled color target the scene renders into; resolved into `color`.
    // VK_NULL_HANDLE when MSAA is off (1x -- we render straight into `color`).
    private long msaaColorImage = VK_NULL_HANDLE;
    private long msaaColorAllocation = VK_NULL_HANDLE;
    private long msaaColorView = VK_NULL_HANDLE;

    // The depth buffer -- N-sample, matching the color target. Always present (any
    // content may depth-test; cheap, and keeps every pipeline kind renderable).
    private long depthImage;
    private long depthAllocation;
    private long depthView;
    private final int depthFormat;

    /**
     * Create an offscreen target of {@code width} x {@code height}. The formats and
     * sample count are passed in by {@link Renderer#createRenderTarget} from the
     * swapchain it owns -- so the target renders like the screen and every baked
     * pipeline accepts it. {@code filter} is how the result is sampled when read
     * back (LINEAR for smooth scaling, NEAREST for pixel-perfect).
     */
    RenderTarget(Device device, int width, int height, int colorFormat,
                 int depthFormat, int sampleCount, Filter filter) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.depthFormat = depthFormat;
        this.sampleCount = sampleCount;

        // The sampleable surface (single-sample) -- resolve dest or direct target.
        this.color = Texture.renderTarget(device, width, height, colorFormat, filter);

        // The multisampled intermediate (only when AA is on).
        if (sampleCount != VK_SAMPLE_COUNT_1_BIT) {
            createMsaaColor(colorFormat);
        }
        // The depth buffer (always), sized to the target + matching its samples.
        createDepth(depthFormat);
    }

    // ------------------------------------------------------------------
    // Accessors -- the render loop (Beat 2) targets these; texture() samples.
    // ------------------------------------------------------------------

    /** The sampleable result, usable anywhere a {@link Texture} is -- {@code
     *  g.image(target.texture(), ...)} or a custom pipeline's texture slot. After a
     *  pass renders into this target, this holds (the resolved) rendered pixels. */
    public Texture texture() { return color; }

    public int width()       { return width; }
    public int height()      { return height; }
    int sampleCount()        { return sampleCount; }
    int depthFormat()        { return depthFormat; }

    // The handles the render loop binds as attachments / transitions with barriers.
    long colorImage()     { return color.image(); }   // single-sample; SHADER_READ barrier target
    long colorView()      { return color.view(); }     // direct target (1x) or resolve dest (MSAA)
    long msaaColorImage() { return msaaColorImage; }
    long msaaColorView()  { return msaaColorView; }
    long depthImage()     { return depthImage; }
    long depthView()      { return depthView; }

    // ------------------------------------------------------------------
    // Image creation -- the same VMA dedicated-attachment recipe as Swapchain's
    // depth/MSAA targets, just owned here per-target instead of per-swapchain.
    // ------------------------------------------------------------------

    /** The N-sample color image the scene renders into (resolved into {@link #color}).
     *  Same format as the sampleable surface -- a resolve cannot change format. */
    private void createMsaaColor(int colorFormat) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.format(colorFormat);
            imageInfo.extent().width(width).height(height).depth(1);
            imageInfo.mipLevels(1);          // multisampled images never have mips (spec rule)
            imageInfo.arrayLayers(1);
            imageInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);  // rendered into, not sampled (we sample the resolve)
            imageInfo.samples(sampleCount);
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(VMA_MEMORY_USAGE_AUTO);
            allocInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);  // full-screen attachment

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(device.allocator(), imageInfo, allocInfo,
                            pImage, pAllocation, null),
                    "Failed to create the render-target MSAA color image");
            msaaColorImage = pImage.get(0);
            msaaColorAllocation = pAllocation.get(0);

            msaaColorView = createView(msaaColorImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT);
        }
    }

    /** The depth buffer: N-sample (matching the color target), DEPTH_STENCIL usage. */
    private void createDepth(int depthFormat) {
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
            imageInfo.samples(sampleCount);   // depth test is per-sample -- must match color
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(VMA_MEMORY_USAGE_AUTO);
            allocInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(device.allocator(), imageInfo, allocInfo,
                            pImage, pAllocation, null),
                    "Failed to create the render-target depth image");
            depthImage = pImage.get(0);
            depthAllocation = pAllocation.get(0);

            depthView = createView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT);
        }
    }

    /** A 2D, single-mip, single-layer view of {@code image} -- the lens dynamic
     *  rendering targets (or, for the color image, the resolve destination). */
    private long createView(long image, int format, int aspect) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspect);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(1);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateImageView(device.handle(), viewInfo, null, pView),
                    "Failed to create a render-target image view");
            return pView.get(0);
        }
    }

    /** Destroy depth + (optional) MSAA color, then the sampleable color Texture.
     *  Reverse order of creation; the Texture frees its own view/sampler/image. */
    public void close() {
        vkDestroyImageView(device.handle(), depthView, null);
        vmaDestroyImage(device.allocator(), depthImage, depthAllocation);
        if (msaaColorImage != VK_NULL_HANDLE) {
            vkDestroyImageView(device.handle(), msaaColorView, null);
            vmaDestroyImage(device.allocator(), msaaColorImage, msaaColorAllocation);
        }
        color.close();
    }
}
