package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memFloatBuffer;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A VkBuffer plus the VkDeviceMemory backing it -- jvre's first GPU memory.
 *
 * Vulkan splits "a buffer" into two objects you wire together yourself:
 *   1. The BUFFER (VkBuffer): a typed handle -- size + usage flags. Owns no
 *      storage at all.
 *   2. The MEMORY (VkDeviceMemory): a raw allocation from one of the GPU's
 *      memory HEAPS, chosen by memory-TYPE index.
 * vkBindBufferMemory marries them. The split exists because real engines
 * allocate few big VkDeviceMemory blocks and sub-allocate many buffers into
 * them (drivers cap total allocations -- maxMemoryAllocationCount can be as
 * low as 4096). We do one allocation per buffer while learning; that job
 * eventually goes to VMA (the Vulkan Memory Allocator).
 *
 * Creative tier: Buffer is one of the objects L1 users will eventually touch
 * (vertex data, uniforms), like Pipeline.
 */
public class Buffer {

    private final Device device;
    private final long handle;  // VkBuffer
    private final long memory;  // VkDeviceMemory backing it
    private final long size;    // bytes

    /**
     * Create a buffer of {@code size} bytes with the given USAGE (what commands
     * may touch it: vertex input, transfer src/dst, ...) backed by memory with
     * the given PROPERTIES (where it lives / who can see it -- see
     * {@link #findMemoryType}).
     */
    public Buffer(Device device, long size, int usage, int memoryProperties) {
        this.device = device;
        this.size = size;

        try (MemoryStack stack = stackPush()) {
            // ---- 1. The buffer object: size + usage, no storage yet ----
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            // Only the graphics queue touches our buffers -> EXCLUSIVE (same
            // single-queue story as the swapchain images).
            bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateBuffer(device.handle(), bufferInfo, null, pBuffer),
                    "Failed to create a buffer (" + size + " bytes)");
            handle = pBuffer.get(0);

            // ---- 2. What does THIS buffer need from memory? ----
            // The driver answers: actual size (may exceed ours for alignment),
            // alignment, and a BITMASK of which memory types are acceptable.
            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device.handle(), handle, memReq);

            // ---- 3. Allocate from a compatible memory type ----
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memReq.size());
            allocInfo.memoryTypeIndex(
                    findMemoryType(stack, memReq.memoryTypeBits(), memoryProperties));

            LongBuffer pMemory = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkAllocateMemory(device.handle(), allocInfo, null, pMemory),
                    "Failed to allocate " + memReq.size() + " bytes of device memory");
            memory = pMemory.get(0);

            // ---- 4. Marry them (offset 0: the allocation is all ours) ----
            Vk.check(vkBindBufferMemory(device.handle(), handle, memory, 0),
                    "Failed to bind buffer memory");
        }
    }

    /**
     * Copy floats into the buffer through the CPU. Requires HOST_VISIBLE
     * memory: vkMapMemory hands us a raw pointer into the allocation, we write,
     * we unmap. With HOST_COHERENT (we always pair it) the write is visible to
     * the GPU without an explicit vkFlushMappedMemoryRanges -- the simple
     * trade: coherent traffic may be a little slower, but there is no
     * flush-forgetting bug to hunt.
     */
    public void uploadFloats(float[] data) {
        long bytes = (long) data.length * Float.BYTES;
        if (bytes > size) {
            throw new IllegalArgumentException(
                    "Upload of " + bytes + " bytes into a " + size + "-byte buffer");
        }
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vk.check(vkMapMemory(device.handle(), memory, 0, bytes, 0, pData),
                    "Failed to map buffer memory");
            // Wrap the raw mapped pointer as a FloatBuffer and bulk-copy.
            memFloatBuffer(pData.get(0), data.length).put(data);
            vkUnmapMemory(device.handle(), memory);
        }
    }

    /** The VkBuffer handle -- for vkCmdBindVertexBuffers / vkCmdCopyBuffer. */
    public long handle() {
        return handle;
    }

    public long size() {
        return size;
    }

    /** Destroy the buffer, then free its memory (child before parent, as ever). */
    public void close() {
        vkDestroyBuffer(device.handle(), handle, null);
        vkFreeMemory(device.handle(), memory, null);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The memory-type hunt. The GPU advertises a small table of memory TYPES
     * (each pointing into a HEAP -- e.g. 24 GB of VRAM, or system RAM). A type
     * is right for us when BOTH:
     *   - the buffer allows it: bit {@code i} set in {@code typeFilter}
     *     (from vkGetBufferMemoryRequirements), AND
     *   - it has every property we asked for: HOST_VISIBLE (CPU can map it),
     *     HOST_COHERENT (no manual flushes), DEVICE_LOCAL (in VRAM, fastest
     *     for the GPU), ...
     * On discrete GPUs HOST_VISIBLE and DEVICE_LOCAL are usually DIFFERENT
     * types -- that split is exactly why staging uploads exist.
     */
    private int findMemoryType(MemoryStack stack, int typeFilter, int required) {
        VkPhysicalDeviceMemoryProperties memProps =
                VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device.physicalDevice(), memProps);

        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            boolean allowed = (typeFilter & (1 << i)) != 0;
            boolean hasProps =
                    (memProps.memoryTypes(i).propertyFlags() & required) == required;
            if (allowed && hasProps) {
                return i;
            }
        }
        throw new RuntimeException("No memory type with properties 0x"
                + Integer.toHexString(required) + " (filter 0x"
                + Integer.toHexString(typeFilter) + ")");
    }
}
