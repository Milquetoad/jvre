package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
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
    private final int depth;    // extent depth: 1 for 2D/cube, N for a 3D volume
    private final int format;   // VkFormat the texels are stored as
    private final int mipLevels; // 1 = no mip chain; >1 = a generated mip pyramid
    private final int arrayLayers; // 1 for plain 2D/3D; 6 for a cubemap (its 6 faces)
    private final int imageType;   // VK_IMAGE_TYPE_2D (flat / cube faces) or _3D (volume)
    private final int viewType;    // how a shader reads it: _2D, _CUBE, or _3D
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
        return create(device, commandPool, pixels, width, height,
                TextureOptions.builder().filter(Filter.NEAREST).build());
    }

    /** {@link #create(Device, long, byte[], int, int)} with an explicit sampling
     *  {@link Filter} (NEAREST for crisp pixels, LINEAR for smooth scaling). */
    static Texture create(Device device, long commandPool,
                                 byte[] pixels, int width, int height, Filter filter) {
        return create(device, commandPool, pixels, width, height,
                TextureOptions.builder().filter(filter).build());
    }

    /** {@link #create(Device, long, byte[], int, int)} with full sampler {@link
     *  TextureOptions} (filter + wrap mode). */
    static Texture create(Device device, long commandPool,
                                 byte[] pixels, int width, int height, TextureOptions opts) {
        // R8G8B8A8_SRGB (4 bytes/texel, GPU linearizes on sample). The sprite/image path.
        return upload(device, commandPool, pixels, width, height,
                VK_FORMAT_R8G8B8A8_SRGB, 4, opts);
    }

    /**
     * Build a CUBEMAP from 6 square faces -- the {@code samplerCube} input for
     * environment maps, skyboxes, and reflection probes. A cubemap is, mechanically,
     * a 6-layer 2D image array tagged {@code CUBE_COMPATIBLE} and read through a
     * {@code VIEW_TYPE_CUBE} view: the sampler takes a 3D DIRECTION vector (not a UV)
     * and the hardware picks the face + texel the ray hits, filtering across face
     * seams for you.
     *
     * <p>The faces are given in Vulkan's fixed layer order -- {@code +X, -X, +Y, -Y,
     * +Z, -Z} -- each {@code size} x {@code size}, R8G8B8A8 (4 bytes/texel). They are
     * staged CONTIGUOUSLY and copied in one shot (each face = one array layer). Cube
     * faces are colour, so the format is sRGB, matching {@link #create}.
     */
    static Texture createCubemap(Device device, long commandPool, byte[][] faces,
                                 int size, TextureOptions opts) {
        if (faces.length != 6) {
            throw new IllegalArgumentException("A cubemap needs exactly 6 faces (+X,-X,+Y,-Y,+Z,-Z), got "
                    + faces.length);
        }
        int faceBytes = size * size * 4;
        // Concatenate the 6 faces into one staging blob, in layer order. The single
        // copy in uploadFrom (layerCount 6) then fills all faces at once.
        byte[] all = new byte[6 * faceBytes];
        for (int f = 0; f < 6; f++) {
            if (faces[f].length != faceBytes) {
                throw new IllegalArgumentException("Cubemap face " + f + " is " + faces[f].length
                        + " bytes; expected " + faceBytes + " (" + size + "x" + size + " RGBA)");
            }
            System.arraycopy(faces[f], 0, all, f * faceBytes, faceBytes);
        }

        Buffer staging = new Buffer(device, all.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        staging.uploadBytes(all);

        Texture texture = new Texture(device, size, size, 1, VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                1, 6, VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_CUBE,
                VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT);
        texture.uploadFrom(commandPool, staging);
        texture.createViewAndSampler(opts);

        staging.close();
        return texture;
    }

    /**
     * Build a 3D VOLUME texture from {@code width} x {@code height} x {@code depth}
     * voxels -- the {@code sampler3D} input for volumetric data, 3D colour LUTs, and
     * raymarched fields. Unlike a cubemap (6 flat layers), this is a genuine 3D
     * IMAGE: a third extent dimension, read through a {@code VIEW_TYPE_3D} view and
     * sampled with a 3D coordinate {@code (u,v,w)} in [0,1] -- the GPU trilinearly
     * filters across all three axes.
     *
     * <p>The voxels are laid out SLICE-MAJOR (all of z=0, then z=1, ...), row-major
     * within each slice, R8G8B8A8 (4 bytes/voxel). They stage contiguously and copy
     * in one shot (the copy's {@code imageExtent.depth} pulls consecutive slices from
     * the buffer). Stored sRGB, matching the colour texture paths.
     */
    static Texture createVolume(Device device, long commandPool, byte[] voxels,
                                int width, int height, int depth, TextureOptions opts) {
        long bytes = (long) width * height * depth * 4;
        if (voxels.length != bytes) {
            throw new IllegalArgumentException("Expected " + bytes + " bytes for a "
                    + width + "x" + height + "x" + depth + " RGBA volume, got " + voxels.length);
        }

        Buffer staging = new Buffer(device, voxels.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        staging.uploadBytes(voxels);

        Texture texture = new Texture(device, width, height, depth, VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                1, 1, VK_IMAGE_TYPE_3D, VK_IMAGE_VIEW_TYPE_3D, 0);
        texture.uploadFrom(commandPool, staging);
        texture.createViewAndSampler(opts);

        staging.close();
        return texture;
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
        return load(device, commandPool, resourcePath,
                TextureOptions.builder().filter(Filter.LINEAR).build());
    }

    /** {@link #load(Device, long, String)} with an explicit sampling {@link Filter}. */
    static Texture load(Device device, long commandPool, String resourcePath, Filter filter) {
        return load(device, commandPool, resourcePath,
                TextureOptions.builder().filter(filter).build());
    }

    /** {@link #load(Device, long, String)} with full sampler {@link TextureOptions}. */
    static Texture load(Device device, long commandPool, String resourcePath, TextureOptions opts) {
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
                return create(device, commandPool, pixels, width, height, opts);
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
                VK_FORMAT_R8_UNORM, 1,
                TextureOptions.builder().filter(Filter.LINEAR).wrap(WrapMode.CLAMP).build());
    }

    /**
     * Build a 4-CHANNEL (RGBA8) texture with a LINEAR sampler -- the L2 MSDF text
     * glyph atlas. Each texel holds an msdfgen multi-channel distance (R, G, B; A
     * unused/255). Critically the format is R8G8B8A8_<b>UNORM</b>, not _SRGB: the
     * channels are signed DISTANCES interpreted in linear space (msdfgen's own
     * rule -- "like the alpha channel, not as sRGB"); an sRGB curve would corrupt
     * the median reconstruction. RGBA (not RGB888) because three-channel sampled
     * formats aren't universally supported, RGBA8 is. LINEAR filtering lets the
     * shader interpolate the field between texels for a clean edge. Same staging
     * upload as the others. See {@link Font#loadMsdf} and shape2d.frag mode 6.
     */
    static Texture createMsdfAtlas(Device device, long commandPool,
                                          byte[] rgba, int width, int height) {
        return upload(device, commandPool, rgba, width, height,
                VK_FORMAT_R8G8B8A8_UNORM, 4,
                TextureOptions.builder().filter(Filter.LINEAR).wrap(WrapMode.CLAMP).build());
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
        // COLOR_ATTACHMENT (rendered into) | SAMPLED (read back as a texture) |
        // TRANSFER_SRC (copied to a buffer for readback -- Renderer.readPixels).
        Texture texture = new Texture(device, width, height, format,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        texture.createViewAndSampler(TextureOptions.builder().filter(filter).wrap(WrapMode.CLAMP).build());
        return texture;
    }

    /** The shared staging upload: stage the pixels, create the image in {@code
     *  format}, copy + transition, then build the view + a sampler from {@code
     *  opts}. {@code bytesPerTexel} sizes the staging buffer for the format. */
    private static Texture upload(Device device, long commandPool, byte[] pixels,
                                  int width, int height, int format,
                                  int bytesPerTexel, TextureOptions opts) {
        long imageBytes = (long) width * height * bytesPerTexel;
        if (pixels.length != imageBytes) {
            throw new IllegalArgumentException("Expected " + imageBytes
                    + " bytes for " + width + "x" + height + " (" + bytesPerTexel
                    + " B/texel), got " + pixels.length);
        }

        Buffer staging = new Buffer(device, imageBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);  // hostVisible: CPU writes the pixels in
        staging.uploadBytes(pixels);

        // Mipmaps: a pyramid of half-size levels, generated GPU-side by blitting.
        // The image then needs TRANSFER_SRC too (each level is the blit source for
        // the next), and the format must support a LINEAR blit (checked up front).
        int mips = opts.mipmaps ? mipLevelsFor(width, height) : 1;
        int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | (mips > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
        if (mips > 1) {
            requireLinearBlit(device, format);
        }
        Texture texture = new Texture(device, width, height, format, usage, mips);
        texture.uploadFrom(commandPool, staging);
        texture.createViewAndSampler(opts);

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
        this(device, width, height, format, usage, 1);
    }

    Texture(Device device, int width, int height, int format, int usage, int mipLevels) {
        // The common 2D case: depth 1, one array layer, a plain 2D image + view.
        this(device, width, height, 1, format, usage, mipLevels, 1,
                VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_2D, 0);
    }

    /**
     * The canonical image constructor, general enough for every kind jvre makes:
     * a flat 2D texture, a 6-face CUBEMAP (arrayLayers 6 + the cube-compatible
     * flag + a CUBE view), or a 3D VOLUME (depth &gt; 1 + a 3D image/view). The
     * 2D convenience constructors above feed it the flat defaults.
     *
     * <p>The three "shape" knobs are not independent -- they come in valid sets:
     * a cube is {@code (depth 1, layers 6, IMAGE_TYPE_2D, VIEW_TYPE_CUBE, CUBE_COMPATIBLE)};
     * a volume is {@code (depth N, layers 1, IMAGE_TYPE_3D, VIEW_TYPE_3D, 0)}. The
     * factories ({@link #createCubemap}, {@link #createVolume}) supply the right set,
     * so callers never assemble an invalid combination by hand.
     */
    Texture(Device device, int width, int height, int depth, int format, int usage,
            int mipLevels, int arrayLayers, int imageType, int viewType, int createFlags) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.format = format;
        this.mipLevels = mipLevels;
        this.arrayLayers = arrayLayers;
        this.imageType = imageType;
        this.viewType = viewType;

        try (MemoryStack stack = stackPush()) {
            // ---- 1. The image object: picture description + tiling + usage ----
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.flags(createFlags);                   // e.g. CUBE_COMPATIBLE for a cubemap
            imageInfo.imageType(imageType);                 // 2D (flat / cube faces) or 3D (volume)
            imageInfo.format(format);                       // texel layout, e.g. R8G8B8A8_SRGB
            imageInfo.extent().width(width).height(height).depth(depth);  // depth>1 == a 3D volume
            imageInfo.mipLevels(mipLevels);                 // 1 = no chain; >1 = mip pyramid
            imageInfo.arrayLayers(arrayLayers);             // 1 = one image; 6 = a cubemap's faces
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
                // One reusable barrier (retargeted between transitions); no
                // queue-family ownership transfer.
                VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
                barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
                barrier.image(image);
                barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                barrier.subresourceRange().baseArrayLayer(0);
                barrier.subresourceRange().layerCount(arrayLayers);   // all 6 faces for a cubemap

                VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack);
                depInfo.sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
                depInfo.pImageMemoryBarriers(barrier);

                // ---- Transition ALL mip levels UNDEFINED -> TRANSFER_DST ----
                // (Just mip 0 when there's no chain.) oldLayout=UNDEFINED discards
                // the (nonexistent) prior contents -- we overwrite the whole image.
                barrier.subresourceRange().baseMipLevel(0).levelCount(mipLevels);
                barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_NONE);
                barrier.srcAccessMask(VK_ACCESS_2_NONE);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
                barrier.dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
                vkCmdPipelineBarrier2(cmd, depInfo);

                // ---- The copy: linear buffer -> tiled image (the BASE level) ----
                // bufferRowLength/Height = 0 means "tightly packed", so the driver
                // derives the source stride from imageExtent. mipLevel 0.
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.bufferOffset(0);
                region.bufferRowLength(0);
                region.bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                region.imageSubresource().mipLevel(0);
                region.imageSubresource().baseArrayLayer(0);
                // One copy moves every layer/slice at once: the staging buffer holds
                // them contiguously (face 0 | face 1 | ... for a cube; slice 0 | ...
                // for a volume), so layerCount/depth pull consecutive buffer regions.
                region.imageSubresource().layerCount(arrayLayers);
                region.imageOffset().x(0).y(0).z(0);
                region.imageExtent().width(width).height(height).depth(depth);
                vkCmdCopyBufferToImage(cmd, staging.handle(), image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

                if (mipLevels > 1) {
                    generateMipmaps(cmd, barrier, depInfo);
                } else {
                    // ---- mip 0: TRANSFER_DST -> SHADER_READ_ONLY (ready to sample) ----
                    barrier.subresourceRange().baseMipLevel(0).levelCount(1);
                    barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                    barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    barrier.srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
                    barrier.srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    barrier.dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);
                    barrier.dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
                    vkCmdPipelineBarrier2(cmd, depInfo);
                }
            }
        });
    }

    /**
     * Build the mip pyramid GPU-side: starting from the full-size base level (just
     * copied in, all levels in TRANSFER_DST), repeatedly BLIT level i-1 down into
     * the half-size level i with LINEAR filtering. Each source level, once blitted
     * FROM, transitions to SHADER_READ_ONLY; the final (smallest) level, only ever
     * a blit destination, transitions at the end. So every level ends sampleable.
     * (The classic vulkan-tutorial routine; blits run on the TRANSFER stage.)
     */
    private void generateMipmaps(VkCommandBuffer cmd, VkImageMemoryBarrier2.Buffer barrier,
                                 VkDependencyInfo depInfo) {
        try (MemoryStack stack = stackPush()) {
            int mipW = width;
            int mipH = height;
            for (int i = 1; i < mipLevels; i++) {
                // level i-1: TRANSFER_DST -> TRANSFER_SRC (it becomes the blit source)
                barrier.subresourceRange().baseMipLevel(i - 1).levelCount(1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
                barrier.srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
                barrier.dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier2(cmd, depInfo);

                int nextW = Math.max(1, mipW / 2);
                int nextH = Math.max(1, mipH / 2);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i - 1).baseArrayLayer(0).layerCount(1);
                blit.srcOffsets(0).x(0).y(0).z(0);
                blit.srcOffsets(1).x(mipW).y(mipH).z(1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i).baseArrayLayer(0).layerCount(1);
                blit.dstOffsets(0).x(0).y(0).z(0);
                blit.dstOffsets(1).x(nextW).y(nextH).z(1);
                vkCmdBlitImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);

                // level i-1 done as a source -> SHADER_READ_ONLY
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
                barrier.srcAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);
                barrier.dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
                vkCmdPipelineBarrier2(cmd, depInfo);

                mipW = nextW;
                mipH = nextH;
            }

            // The last level was only ever a blit DST -> SHADER_READ_ONLY.
            barrier.subresourceRange().baseMipLevel(mipLevels - 1).levelCount(1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
            barrier.srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT);
            barrier.dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);
            barrier.dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
            vkCmdPipelineBarrier2(cmd, depInfo);
        }
    }

    /** Mip-chain length for a {@code width} x {@code height} image: floor(log2(max
     *  dimension)) + 1 (down to a 1x1 level). */
    private static int mipLevelsFor(int width, int height) {
        return 1 + (int) (Math.floor(Math.log(Math.max(width, height)) / Math.log(2)));
    }

    /** Mip generation blits with a LINEAR filter, which the format must support in
     *  optimal tiling -- verify it (true for R8G8B8A8 on desktop) and fail clearly
     *  otherwise, rather than letting validation flag the blit. */
    private static void requireLinearBlit(Device device, int format) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.physicalDevice(), format, props);
            if ((props.optimalTilingFeatures()
                    & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) == 0) {
                throw new RuntimeException("Texture format " + format
                        + " does not support linear-filtered blits, so mipmaps can't be generated for it");
            }
        }
    }

    /**
     * Create the two objects a shader samples THROUGH: a VkImageView (the typed
     * lens onto this image's one mip / one layer) and a VkSampler (the
     * fixed-function filtering/addressing unit). Called only on the sampled
     * texture path ({@link #create}); a render-target image would need a view
     * but no sampler.
     */
    private void createViewAndSampler(TextureOptions opts) {
        try (MemoryStack stack = stackPush()) {
            // ---- Image view: identical idea to the swapchain image views ----
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(viewType);                         // _2D, _CUBE, or _3D
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(mipLevels);   // the whole pyramid
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(arrayLayers); // 6 for a cube view

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
            samplerInfo.magFilter(opts.filter.vk);
            samplerInfo.minFilter(opts.filter.vk);
            // Address mode = behavior for UVs outside [0,1] (the caller's WrapMode).
            // CLAMP stretches the edge texel (sane sprite default); REPEAT/MIRROR tile
            // (terrain, patterns); BORDER samples the border colour below.
            samplerInfo.addressModeU(opts.wrap.vk);
            samplerInfo.addressModeV(opts.wrap.vk);
            samplerInfo.addressModeW(opts.wrap.vk);
            // Anisotropy (caller's choice): sharper textures at grazing angles. Only
            // if requested AND the device enabled the feature (maxAnisotropy > 1);
            // clamp to the device max. Needs mips to matter.
            boolean aniso = opts.anisotropy && device.maxAnisotropy() > 1.0f;
            samplerInfo.anisotropyEnable(aniso);
            samplerInfo.maxAnisotropy(aniso ? device.maxAnisotropy() : 1.0f);
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
            // false = normalized [0,1] UVs (the universal convention), not raw texels.
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);   // not a shadow-map compare sampler
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);
            // Mip sampling: with a chain, LINEAR mip mode (trilinear) blends between
            // levels and maxLod opens the whole pyramid; without one, level 0 only.
            samplerInfo.mipmapMode(mipLevels > 1
                    ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST);
            samplerInfo.minLod(0.0f);
            samplerInfo.maxLod(mipLevels > 1 ? (float) mipLevels : 0.0f);
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

    /** This texture's VkImageViewType (_2D, _CUBE, or _3D) -- how a shader reads it.
     *  The renderer matches it against an effect channel's declared sampler kind. */
    int viewType() { return viewType; }

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
