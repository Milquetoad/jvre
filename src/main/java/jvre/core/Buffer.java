package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memFloatBuffer;
import static org.lwjgl.system.MemoryUtil.memShortBuffer;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A VkBuffer plus the VMA ALLOCATION backing it.
 *
 * History note (the learning arc): the first Buffer did Vulkan memory by hand --
 * create the VkBuffer, query its requirements, HUNT a compatible memory type,
 * vkAllocateMemory one dedicated VkDeviceMemory, bind. Educational, and exactly
 * what the best-practices layer warned about: drivers cap total allocations
 * (maxMemoryAllocationCount may be as low as 4096) and vkAllocateMemory is slow,
 * so real engines allocate FEW big blocks and SUB-allocate many resources into
 * them. That allocator is a library-sized problem -- VMA (AMD's Vulkan Memory
 * Allocator) is the industry-standard answer, and {@link Device} now owns one.
 *
 * What changed at this seam:
 *   - vmaCreateBuffer does create+place+bind in one call; we hold a VmaAllocation
 *     handle instead of raw VkDeviceMemory (the memory block is VMA's business,
 *     shared with other resources).
 *   - Memory-type FLAGS became INTENT: callers say {@code hostVisible} (CPU will
 *     write it: staging, UBOs) or not (VRAM-resident: vertex/index data). VMA
 *     picks the memory type -- the hand-rolled hunt retired with the manual path
 *     (preserved in git history and the vault notes).
 *   - Mapping goes through vmaMapMemory/vmaUnmapMemory, and uploads finish with
 *     vmaFlushAllocation: a no-op on coherent memory (the desktop norm) and the
 *     required flush on anything else -- correctness by default, no
 *     flush-forgetting bug class.
 *
 * Creative tier: Buffer is one of the objects L1 users will eventually touch
 * (vertex data, uniforms), like Pipeline.
 */
public class Buffer {

    private final Device device;
    private final long handle;      // VkBuffer
    private final long allocation;  // VmaAllocation -- our slice of a VMA-owned block
    private final long size;        // bytes

    /**
     * Build a VRAM-resident buffer filled with the given floats, via the
     * canonical STAGING upload. On a discrete GPU the CPU usually cannot map
     * VRAM at all, so the data takes two hops:
     *
     *   CPU writes  -> staging buffer (host-visible,  TRANSFER_SRC)
     *   GPU copies  -> result buffer  (device-local,  TRANSFER_DST | usage)
     *
     * The copy is a GPU command like any other (Commands.oneShot on the graphics
     * queue -- every graphics queue implicitly supports transfer). The staging
     * buffer is destroyed as soon as the copy lands.
     */
    static Buffer deviceLocal(Device device, long commandPool, float[] data, int usage) {
        Buffer staging = stagingBuffer(device, (long) data.length * Float.BYTES);
        staging.uploadFloats(data);
        return promoteToDeviceLocal(device, commandPool, staging, usage);
    }

    /** Same staging upload, for 16-bit data (e.g. UINT16 index buffers). */
    static Buffer deviceLocal(Device device, long commandPool, short[] data, int usage) {
        Buffer staging = stagingBuffer(device, (long) data.length * Short.BYTES);
        staging.uploadShorts(data);
        return promoteToDeviceLocal(device, commandPool, staging, usage);
    }

    private static Buffer stagingBuffer(Device device, long bytes) {
        return new Buffer(device, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
    }

    /** A host-READABLE staging buffer to copy GPU data back into (TRANSFER_DST +
     *  HOST_ACCESS_RANDOM) -- the readback counterpart of {@link #stagingBuffer}.
     *  Fill it with a {@code vkCmdCopy*ToBuffer} then read via {@link #downloadBytes}. */
    static Buffer readback(Device device, long bytes) {
        return new Buffer(device, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, HostAccess.READ);
    }

    /** Copy a filled staging buffer into a fresh device-local one, then destroy it. */
    private static Buffer promoteToDeviceLocal(Device device, long commandPool,
                                               Buffer staging, int usage) {
        Buffer result = new Buffer(device, staging.size(),
                VK_BUFFER_USAGE_TRANSFER_DST_BIT | usage, false);
        copy(device, commandPool, staging, result, staging.size());
        staging.close();  // served its purpose; the data lives in VRAM now
        return result;
    }

    /**
     * Create a buffer of {@code size} bytes with the given USAGE (what commands
     * may touch it: vertex input, transfer src/dst, ...). {@code hostVisible}
     * declares INTENT, not a memory type: true = the CPU will write into it
     * sequentially (staging buffers, per-frame UBOs), false = GPU-only residency
     * (VMA prefers VRAM). VMA translates intent into a concrete memory type and
     * places the buffer inside one of its big shared blocks.
     */
    /**
     * How (if at all) the CPU touches this buffer's memory -- the intent VMA turns
     * into a memory type. NONE = GPU-only (VRAM). WRITE = the CPU streams data IN
     * only (staging uploads, UBOs); the cheapest host-visible flavor (write-combined
     * memory, slow to READ). READ = the CPU reads data back OUT (readback staging);
     * needs randomly-readable host memory.
     */
    enum HostAccess { NONE, WRITE, READ }

    Buffer(Device device, long size, int usage, boolean hostVisible) {
        this(device, size, usage, hostVisible ? HostAccess.WRITE : HostAccess.NONE);
    }

    Buffer(Device device, long size, int usage, HostAccess access) {
        this.device = device;
        this.size = size;

        try (MemoryStack stack = stackPush()) {
            // The buffer description is unchanged from the manual days -- VMA
            // replaces the MEMORY half, not the buffer object itself.
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            // Only the graphics queue touches our buffers -> EXCLUSIVE (same
            // single-queue story as the swapchain images).
            bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            // The intent declaration. AUTO lets VMA choose the memory type. SEQUENTIAL
            // _WRITE = "CPU streams IN, never reads" (write-combined is fine); RANDOM
            // = "CPU reads back" (readback staging -- needs host-cached/readable mem).
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(VMA_MEMORY_USAGE_AUTO);
            if (access == HostAccess.WRITE) {
                allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            } else if (access == HostAccess.READ) {
                allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            }

            // One call: create the VkBuffer, find/grow a block, bind at an offset.
            LongBuffer pBuffer = stack.longs(VK_NULL_HANDLE);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vk.check(vmaCreateBuffer(device.allocator(), bufferInfo, allocInfo,
                            pBuffer, pAllocation, null),
                    "Failed to create a buffer (" + size + " bytes)");
            handle = pBuffer.get(0);
            allocation = pAllocation.get(0);
        }
    }

    /**
     * Copy floats into the buffer through the CPU. Requires a {@code hostVisible}
     * buffer: vmaMapMemory hands us a raw pointer into the allocation's slice of
     * its block, we write, we unmap, and vmaFlushAllocation makes the write
     * visible to the GPU (a no-op when VMA picked coherent memory -- the desktop
     * norm -- and the required flush anywhere else).
     */
    void uploadFloats(float[] data) {
        uploadFloats(data, data.length);
    }

    /**
     * Upload only the first {@code count} floats of {@code data} -- for a
     * growable arena (e.g. the L2 shape vertex buffer) whose backing array is
     * oversized and only partly live each frame.
     */
    void uploadFloats(float[] data, int count) {
        long bytes = (long) count * Float.BYTES;
        checkFits(bytes);
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vk.check(vmaMapMemory(device.allocator(), allocation, pData),
                    "Failed to map buffer memory");
            // Wrap the raw mapped pointer as a FloatBuffer and bulk-copy the
            // live prefix.
            memFloatBuffer(pData.get(0), count).put(data, 0, count);
            vmaUnmapMemory(device.allocator(), allocation);
            vmaFlushAllocation(device.allocator(), allocation, 0, bytes);
        }
    }

    /** Same as {@link #uploadFloats}, for 16-bit values (index data). */
    void uploadShorts(short[] data) {
        long bytes = (long) data.length * Short.BYTES;
        checkFits(bytes);
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vk.check(vmaMapMemory(device.allocator(), allocation, pData),
                    "Failed to map buffer memory");
            memShortBuffer(pData.get(0), data.length).put(data);
            vmaUnmapMemory(device.allocator(), allocation);
            vmaFlushAllocation(device.allocator(), allocation, 0, bytes);
        }
    }

    /**
     * Same as {@link #uploadFloats}, for raw bytes -- used to stage texture
     * PIXELS (e.g. R8G8B8A8 = 4 bytes/texel) into a host-visible buffer before
     * the GPU copies them into an image ({@link Texture}).
     */
    void uploadBytes(byte[] data) {
        long bytes = data.length;
        checkFits(bytes);
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vk.check(vmaMapMemory(device.allocator(), allocation, pData),
                    "Failed to map buffer memory");
            memByteBuffer(pData.get(0), data.length).put(data);
            vmaUnmapMemory(device.allocator(), allocation);
            vmaFlushAllocation(device.allocator(), allocation, 0, bytes);
        }
    }

    /**
     * Read this buffer's bytes back into {@code dst} through the CPU -- the inverse
     * of {@link #uploadBytes}, for a {@link #readback} buffer the GPU has copied
     * into. vmaInvalidateAllocation makes the GPU's writes visible to the CPU before
     * we read (a no-op on coherent memory -- the desktop norm -- and the required
     * cache invalidate anywhere else, the mirror of the upload flush).
     */
    void downloadBytes(byte[] dst) {
        long bytes = dst.length;
        checkFits(bytes);
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vk.check(vmaMapMemory(device.allocator(), allocation, pData),
                    "Failed to map buffer memory for readback");
            vmaInvalidateAllocation(device.allocator(), allocation, 0, bytes);
            memByteBuffer(pData.get(0), dst.length).get(dst);
            vmaUnmapMemory(device.allocator(), allocation);
        }
    }

    /** The VkBuffer handle -- for vkCmdBindVertexBuffers / vkCmdCopyBuffer. */
    long handle() {
        return handle;
    }

    long size() {
        return size;
    }

    /** Destroy the buffer and release its slice back to VMA's block in one call. */
    public void close() {
        vmaDestroyBuffer(device.allocator(), handle, allocation);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void checkFits(long bytes) {
        if (bytes > size) {
            throw new IllegalArgumentException(
                    "Upload of " + bytes + " bytes into a " + size + "-byte buffer");
        }
    }

    /** Record + submit a one-shot GPU copy from src to dst, and wait for it. */
    private static void copy(Device device, long commandPool, Buffer src, Buffer dst, long bytes) {
        Commands.oneShot(device, commandPool, cmd -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.size(bytes);  // src/dst offsets stay 0
                vkCmdCopyBuffer(cmd, src.handle(), dst.handle(), region);
            }
        });
    }
}
