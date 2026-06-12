package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * A VkImage plus the VkDeviceMemory backing it -- the GPU's native PICTURE
 * object, and jvre's first non-buffer resource.
 *
 * An image is NOT just a 2D buffer. Three differences, all in service of the
 * GPU's texture-sampling hardware:
 *
 *   1. TILING. A {@link Buffer} is row-major (texel (x+1,y) follows (x,y)). But
 *      sampling reads little 2D neighborhoods, so GPUs store textures in a
 *      driver-private SWIZZLED layout (VK_IMAGE_TILING_OPTIMAL) where
 *      2D-adjacent texels are memory-adjacent -- cache-friendly, but the byte
 *      arrangement is secret and the CPU cannot meaningfully write into it.
 *      That is exactly why filling an image needs a staging buffer + a GPU-side
 *      copy (the next beat): the CPU writes a normal linear buffer, the GPU
 *      re-tiles it into the image.
 *
 *   2. LAYOUT. Because the optimal byte arrangement depends on what the image is
 *      being USED for (sampled vs. rendered-into vs. copied-into), an image
 *      carries a CURRENT LAYOUT that you transition between with the same
 *      VkImageMemoryBarrier2 machinery the swapchain images already use. A
 *      sampled texture's journey: UNDEFINED (here) -> TRANSFER_DST (receive the
 *      copy) -> SHADER_READ_ONLY (be sampled). UNDEFINED = "no promised
 *      contents", correct for a fresh image we are about to overwrite.
 *
 *   3. It describes a PICTURE, not a size: format (texel layout), extent
 *      (w x h x depth), mip levels, array layers, sample count -- none of which
 *      a buffer has.
 *
 * The MEMORY half, though, is identical to Buffer: ask requirements, find a
 * compatible memory type ({@link Device#findMemoryType}), allocate, bind. (One
 * allocation per image while learning -- VMA inherits the job, same standing
 * ticket as Buffer.)
 *
 * Creative tier: Texture is an object L1 users will eventually touch, like
 * Buffer and Pipeline.
 */
public class Texture {

    private final Device device;
    private final long image;   // VkImage -- the picture handle (owns no storage)
    private final long memory;  // VkDeviceMemory backing it
    private final int width;
    private final int height;
    private final int format;   // VkFormat the texels are stored as
    private long view = VK_NULL_HANDLE;     // VkImageView -- the typed lens shaders read through
    private long sampler = VK_NULL_HANDLE;  // VkSampler  -- how shaders filter + address it

    /**
     * Build a sampleable texture from CPU pixels via the canonical STAGING
     * upload -- the image analog of {@link Buffer#deviceLocal}. The pixels are
     * R8G8B8A8 (4 bytes/texel, row-major top-to-bottom); the image format is
     * R8G8B8A8_SRGB so the GPU LINEARIZES on sample (correct color, consistent
     * with the sRGB swapchain). The data takes the same two hops as a
     * device-local buffer, but the second hop also RE-TILES it:
     *
     *   CPU writes -> staging buffer (HOST_VISIBLE, TRANSFER_SRC, linear)
     *   GPU copies -> image          (DEVICE_LOCAL, TRANSFER_DST|SAMPLED, tiled)
     *
     * plus the layout transitions the image needs around the copy. The staging
     * buffer is destroyed once the pixels live in the image.
     */
    public static Texture create(Device device, long commandPool,
                                 byte[] pixels, int width, int height) {
        long imageBytes = (long) width * height * 4;  // R8G8B8A8 = 4 bytes/texel
        if (pixels.length != imageBytes) {
            throw new IllegalArgumentException("Expected " + imageBytes
                    + " bytes for " + width + "x" + height + " RGBA, got " + pixels.length);
        }

        Buffer staging = new Buffer(device, imageBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        staging.uploadBytes(pixels);

        Texture texture = new Texture(device, width, height, VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        texture.uploadFrom(commandPool, staging);
        texture.createViewAndSampler();

        staging.close();  // served its purpose; the pixels live in the image now
        return texture;
    }

    /**
     * Create an image of {@code width} x {@code height} in {@code format}, with
     * the given USAGE (what may touch it: TRANSFER_DST to copy into, SAMPLED to
     * be read by a shader, ...) backed by memory with the given PROPERTIES
     * (DEVICE_LOCAL = VRAM, where the GPU samples fastest). The image starts in
     * layout UNDEFINED with no contents; filling + transitioning it is the next
     * beat.
     */
    public Texture(Device device, int width, int height, int format,
                   int usage, int memoryProperties) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.format = format;

        try (MemoryStack stack = stackPush()) {
            // ---- 1. The image object: picture description + tiling + usage ----
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);          // a flat 2D picture (1D / 3D also exist)
            imageInfo.format(format);                       // texel layout, e.g. R8G8B8A8_SRGB
            imageInfo.extent().width(width).height(height).depth(1);  // depth 1 == 2D
            imageInfo.mipLevels(1);                         // no mip chain yet (a later beat)
            imageInfo.arrayLayers(1);                       // one image (not an array / atlas-layer set)
            imageInfo.tiling(VK_IMAGE_TILING_OPTIMAL);      // driver's private swizzle; NOT CPU-mappable
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);  // contents undefined; transition before use
            imageInfo.usage(usage);                         // TRANSFER_DST | SAMPLED for a sampled texture
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);    // graphics queue only (same as our buffers)
            imageInfo.samples(VK_SAMPLE_COUNT_1_BIT);       // no MSAA (that is its own milestone)

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateImage(device.handle(), imageInfo, null, pImage),
                    "Failed to create a " + width + "x" + height + " image");
            image = pImage.get(0);

            // ---- 2. What does THIS image need from memory? ----
            // Like buffers, the driver answers actual size + alignment + a
            // bitmask of acceptable memory types -- but via the IMAGE query.
            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device.handle(), image, memReq);

            // ---- 3. Allocate from a compatible memory type ----
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memReq.size());
            allocInfo.memoryTypeIndex(
                    device.findMemoryType(memReq.memoryTypeBits(), memoryProperties));

            LongBuffer pMemory = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkAllocateMemory(device.handle(), allocInfo, null, pMemory),
                    "Failed to allocate image memory");
            memory = pMemory.get(0);

            // ---- 4. Marry them (offset 0: the allocation is all ours) ----
            Vk.check(vkBindImageMemory(device.handle(), image, memory, 0),
                    "Failed to bind image memory");
        }
    }

    /**
     * Move the staged pixels into this image, recorded as ONE one-shot command:
     *   transition UNDEFINED -> TRANSFER_DST_OPTIMAL   (ready to be copied into)
     *   copy       buffer (linear) -> image (GPU re-tiles into OPTIMAL)
     *   transition TRANSFER_DST -> SHADER_READ_ONLY    (ready to be sampled)
     *
     * The transitions reuse the SAME VkImageMemoryBarrier2 machinery as the
     * swapchain images -- only the layouts/stages/access masks differ. The
     * stage+access pairs are the dependency: each transition WAITS for the prior
     * step's writes and MAKES them available to the next step's reads.
     */
    private void uploadFrom(long commandPool, Buffer staging) {
        Commands.oneShot(device, commandPool, cmd -> {
            try (MemoryStack stack = stackPush()) {
                // One reusable barrier targeting this image's whole color
                // subresource (1 mip, 1 layer); retargeted between the two
                // transitions. No queue-family ownership transfer.
                VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
                barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
                barrier.image(image);
                barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                barrier.subresourceRange().baseMipLevel(0);
                barrier.subresourceRange().levelCount(1);
                barrier.subresourceRange().baseArrayLayer(0);
                barrier.subresourceRange().layerCount(1);

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack);
                depInfo.sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
                depInfo.pImageMemoryBarriers(barrier);

                // ---- Transition 1: UNDEFINED -> TRANSFER_DST ----
                // Nothing precedes (NONE); the COPY stage's write must wait for
                // the layout to be in place. oldLayout=UNDEFINED discards the
                // (nonexistent) prior contents -- we overwrite the whole image.
                barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_NONE);
                barrier.srcAccessMask(VK_ACCESS_2_NONE);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT);
                barrier.dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
                vkCmdPipelineBarrier2(cmd, depInfo);

                // ---- The copy: linear buffer -> tiled image ----
                // bufferRowLength/Height = 0 means "tightly packed", so the
                // driver derives the source stride from imageExtent. The
                // imageSubresource picks WHICH mip/layer (mip 0, layer 0).
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.bufferOffset(0);
                region.bufferRowLength(0);
                region.bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                region.imageSubresource().mipLevel(0);
                region.imageSubresource().baseArrayLayer(0);
                region.imageSubresource().layerCount(1);
                region.imageOffset().x(0).y(0).z(0);
                region.imageExtent().width(width).height(height).depth(1);
                vkCmdCopyBufferToImage(cmd, staging.handle(), image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

                // ---- Transition 2: TRANSFER_DST -> SHADER_READ_ONLY ----
                // Wait for the copy's write (COPY stage), make it visible to the
                // FRAGMENT shader's sampled reads. After this the image is ready
                // to be bound + sampled in every later frame.
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT);
                barrier.srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);
                barrier.dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
                vkCmdPipelineBarrier2(cmd, depInfo);
            }
        });
    }

    /**
     * Create the two objects a shader samples THROUGH: a VkImageView (the typed
     * lens onto this image's one mip / one layer) and a VkSampler (the
     * fixed-function filtering/addressing unit). Called only on the sampled
     * texture path ({@link #create}); a render-target image would need a view
     * but no sampler.
     */
    private void createViewAndSampler() {
        try (MemoryStack stack = stackPush()) {
            // ---- Image view: identical idea to the swapchain image views ----
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(1);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateImageView(device.handle(), viewInfo, null, pView),
                    "Failed to create the texture image view");
            view = pView.get(0);

            // ---- Sampler: coordinate -> color. THE pixel-art knob lives here ----
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            // NEAREST = take the single closest texel, no blending -> crisp, hard
            // pixel edges (what pixel art wants). LINEAR would bilerp the 4 nearest
            // texels and smear the grid into mush. mag = texture drawn LARGER than
            // its texel grid (the common sprite case); min = drawn smaller.
            samplerInfo.magFilter(VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK_FILTER_NEAREST);
            // Address mode = behavior for UVs outside [0,1]. CLAMP_TO_EDGE stretches
            // the edge texel (sane sprite default; REPEAT would tile, for terrain).
            samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            // Anisotropy OFF: it needs a device feature we don't enable, and does
            // nothing for NEAREST without mips anyway.
            samplerInfo.anisotropyEnable(false);
            samplerInfo.maxAnisotropy(1.0f);
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
            // false = normalized [0,1] UVs (the universal convention), not raw texels.
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);   // not a shadow-map compare sampler
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);
            // No mip chain yet -> NEAREST mip mode, LOD clamped to level 0.
            samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
            samplerInfo.minLod(0.0f);
            samplerInfo.maxLod(0.0f);
            samplerInfo.mipLodBias(0.0f);

            LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateSampler(device.handle(), samplerInfo, null, pSampler),
                    "Failed to create the texture sampler");
            sampler = pSampler.get(0);
        }
    }

    /** The VkImage handle -- for layout barriers, copies, and the image view. */
    public long image() { return image; }

    /** The VkImageView -- bound into a descriptor so a shader can sample it. */
    public long view() { return view; }

    /** The VkSampler -- paired with the view in a COMBINED_IMAGE_SAMPLER descriptor. */
    public long sampler() { return sampler; }

    public int width()  { return width; }
    public int height() { return height; }
    public int format() { return format; }

    /** Destroy sampler + view, then the image, then free its memory (children first). */
    public void close() {
        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device.handle(), sampler, null);
        }
        if (view != VK_NULL_HANDLE) {
            vkDestroyImageView(device.handle(), view, null);
        }
        vkDestroyImage(device.handle(), image, null);
        vkFreeMemory(device.handle(), memory, null);
    }
}
