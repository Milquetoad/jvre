package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.util.vma.Vma.*;
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
 * The MEMORY half, though, is identical to Buffer: VMA-backed (vmaCreateImage
 * creates, places, and binds in one call; the hand-rolled
 * requirements/type-hunt/allocate/bind path is preserved in git history and
 * the vault notes).
 *
 * Creative tier: Texture is an object L1 users will eventually touch, like
 * Buffer and Pipeline.
 */
public class Texture {

    private final Device device;
    private final long image;       // VkImage -- the picture handle (owns no storage)
    private final long allocation;  // VmaAllocation -- our slice of a VMA-owned block
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
    static Texture create(Device device, long commandPool,
                                 byte[] pixels, int width, int height) {
        // NEAREST: the default for hand-authored exact pixels (crisp, no blur).
        return create(device, commandPool, pixels, width, height, Filter.NEAREST);
    }

    /** {@link #create(Device, long, byte[], int, int)} with an explicit sampling
     *  {@link Filter} (NEAREST for crisp pixels, LINEAR for smooth scaling). */
    static Texture create(Device device, long commandPool,
                                 byte[] pixels, int width, int height, Filter filter) {
        // R8G8B8A8_SRGB (4 bytes/texel, GPU linearizes on sample). The sprite/image path.
        return upload(device, commandPool, pixels, width, height,
                VK_FORMAT_R8G8B8A8_SRGB, 4, filter.vk);
    }

    /**
     * Load + decode an image FILE from the classpath ({@code resourcePath}, e.g.
     * {@code "/images/sprite.png"}) into a sampleable texture. Decoding is done by
     * stb_image (the same stb family that bakes the font atlas) -- PNG, JPEG, BMP,
     * TGA, GIF, etc. -- forced to 4-channel R8G8B8A8, then handed to the SAME
     * staging upload as {@link #create}. Decoding image formats is tangential to
     * the rendering core, so jvre leans on the proven library rather than parsing
     * pixels by hand.
     *
     * <p>The decoded pixels are treated as sRGB color (the format is
     * R8G8B8A8_SRGB). Sampling defaults to {@link Filter#LINEAR} (smooth scaling --
     * the right default for a decoded image asset); pass an explicit filter to
     * override. (Mipmaps are a later refinement.)
     */
    static Texture load(Device device, long commandPool, String resourcePath) {
        return load(device, commandPool, resourcePath, Filter.LINEAR);
    }

    /** {@link #load(Device, long, String)} with an explicit sampling {@link Filter}. */
    static Texture load(Device device, long commandPool, String resourcePath, Filter filter) {
        ByteBuffer fileBytes = readResource(resourcePath);   // native buffer -- memFree below
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channelsInFile = stack.mallocInt(1);
            // desired_channels = 4: always give us RGBA regardless of the source
            // (a JPEG with no alpha comes back opaque, a paletted PNG expanded).
            ByteBuffer decoded = stbi_load_from_memory(fileBytes, w, h, channelsInFile, 4);
            if (decoded == null) {
                throw new RuntimeException("stb_image failed to decode " + resourcePath
                        + ": " + stbi_failure_reason());
            }
            int width = w.get(0);
            int height = h.get(0);
            try {
                // Copy the decoded native pixels into a byte[] for the shared
                // (tested) create() path. One copy at load time -- negligible.
                byte[] pixels = new byte[width * height * 4];
                decoded.get(pixels);
                return create(device, commandPool, pixels, width, height, filter);
            } finally {
                // get() advanced decoded's position to the end. stb_image_free frees
                // the pointer AT THE BUFFER'S CURRENT POSITION (LWJGL uses the
                // position-sensitive memAddress, not the base memAddress0), so we
                // MUST rewind to 0 first -- freeing a mid-buffer address corrupts
                // the native heap (a hard 0xC0000374 crash).
                decoded.rewind();
                stbi_image_free(decoded);
            }
        } finally {
            memFree(fileBytes);
        }
    }

    /** Read a classpath resource into a native {@link ByteBuffer} (caller frees it
     *  with {@code memFree}) -- what stb_image decodes from. */
    private static ByteBuffer readResource(String path) {
        try (InputStream in = Texture.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Image resource not found on the classpath: " + path);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image resource: " + path, e);
        }
    }

    /**
     * Build a SINGLE-CHANNEL (R8) texture with a LINEAR sampler -- the L2 text
     * glyph atlas. The bytes are one coverage/distance value per texel (the SDF
     * the font baker produced). R8_UNORM keeps the value LINEAR (it is a distance,
     * not a color -- no sRGB curve), and LINEAR filtering is what lets the
     * fragment shader smoothstep a clean anti-aliased edge between texels. Same
     * staging upload as {@link #create}, just a narrower format + smarter filter.
     */
    static Texture createSdfAtlas(Device device, long commandPool,
                                         byte[] coverage, int width, int height) {
        return upload(device, commandPool, coverage, width, height,
                VK_FORMAT_R8_UNORM, 1, VK_FILTER_LINEAR);
    }

    /**
     * Build a sampleable color image we RENDER INTO rather than upload pixels into
     * -- the {@link RenderTarget}'s presentable/sampled surface. The difference
     * from {@link #create} is all in the usage and the (absent) data path:
     *   - usage = COLOR_ATTACHMENT (be rendered into) | SAMPLED (be read back as a
     *     texture later). That dual usage IS render-to-texture -- the same image is
     *     a draw destination in one pass and a sampler2D source in the next.
     *   - no pixels, no staging upload: the image is born UNDEFINED and gets its
     *     contents from a render pass, not a buffer copy. The render loop owns its
     *     layout journey (UNDEFINED -> COLOR_ATTACHMENT -> SHADER_READ_ONLY).
     * It still gets a view + sampler so it is, structurally, an ordinary Texture --
     * which is the whole point: a target drops into {@code g.image(...)} and
     * custom-pipeline texture slots unchanged. Single-sample (it is the RESOLVE
     * destination when the target is multisampled, or the direct target when not).
     */
    static Texture renderTarget(Device device, int width, int height, int format, Filter filter) {
        Texture texture = new Texture(device, width, height, format,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        texture.createViewAndSampler(filter.vk);
        return texture;
    }

    /** The shared staging upload: stage the pixels, create the image in {@code
     *  format}, copy + transition, then build the view + a sampler of {@code
     *  filter}. {@code bytesPerTexel} sizes the staging buffer for the format. */
    private static Texture upload(Device device, long commandPool, byte[] pixels,
                                  int width, int height, int format,
                                  int bytesPerTexel, int filter) {
        long imageBytes = (long) width * height * bytesPerTexel;
        if (pixels.length != imageBytes) {
            throw new IllegalArgumentException("Expected " + imageBytes
                    + " bytes for " + width + "x" + height + " (" + bytesPerTexel
                    + " B/texel), got " + pixels.length);
        }

        Buffer staging = new Buffer(device, imageBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);  // hostVisible: CPU writes the pixels in
        staging.uploadBytes(pixels);

        Texture texture = new Texture(device, width, height, format,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        texture.uploadFrom(commandPool, staging);
        texture.createViewAndSampler(filter);

        staging.close();  // served its purpose; the pixels live in the image now
        return texture;
    }

    /**
     * Create an image of {@code width} x {@code height} in {@code format}, with
     * the given USAGE (what may touch it: TRANSFER_DST to copy into, SAMPLED to
     * be read by a shader, ...), VMA-backed in GPU-preferred memory (VRAM, where
     * the GPU samples fastest). The image starts in layout UNDEFINED with no
     * contents; filling + transitioning it comes after.
     */
    Texture(Device device, int width, int height, int format, int usage) {
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

            // ---- 2. The memory: one VMA call (create + place + bind) ----
            // The manual path (query requirements -> hunt a memory type ->
            // vkAllocateMemory -> vkBindImageMemory) retired with Buffer's; VMA
            // places the image inside one of its big shared blocks. AUTO with no
            // host-access flags = GPU-only residency (OPTIMAL-tiled images are
            // never CPU-mapped anyway -- that's what staging is for).
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(VMA_MEMORY_USAGE_AUTO);

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(device.allocator(), imageInfo, allocInfo,
                            pImage, pAllocation, null),
                    "Failed to create a " + width + "x" + height + " image");
            image = pImage.get(0);
            allocation = pAllocation.get(0);
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
    private void createViewAndSampler(int filter) {
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
            // The filter (caller's choice): NEAREST = the single closest texel, no
            // blending -> crisp, hard pixel edges (what pixel art wants); LINEAR =
            // bilerp the 4 nearest texels (smears a color image, but is exactly
            // what an SDF atlas needs -- a smooth distance gradient to threshold).
            // mag = drawn LARGER than the texel grid; min = drawn smaller.
            samplerInfo.magFilter(filter);
            samplerInfo.minFilter(filter);
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
    long image() { return image; }

    /** The VkImageView -- bound into a descriptor so a shader can sample it. */
    long view() { return view; }

    /** The VkSampler -- paired with the view in a COMBINED_IMAGE_SAMPLER descriptor. */
    long sampler() { return sampler; }

    /** The texture's pixel dimensions -- public on the opaque handle. */
    public int width()  { return width; }
    public int height() { return height; }
    int format() { return format; }

    /** Destroy sampler + view, then the image (its slice returns to VMA's block). */
    public void close() {
        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device.handle(), sampler, null);
        }
        if (view != VK_NULL_HANDLE) {
            vkDestroyImageView(device.handle(), view, null);
        }
        vmaDestroyImage(device.allocator(), image, allocation);
    }
}
