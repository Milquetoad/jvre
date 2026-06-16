package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Run a short burst of GPU commands ONCE and block until it finishes -- the
 * pattern behind every startup transfer (buffer copies, image uploads + their
 * layout transitions). Allocate a throwaway primary command buffer from the
 * pool, record into it (ONE_TIME_SUBMIT), submit on the graphics queue, wait
 * idle, free.
 *
 * Crude on purpose: vkQueueWaitIdle stalls the whole queue, which is fine for
 * one-time STARTUP uploads -- and it is itself a full memory barrier, so reads
 * that follow later are safe. Streaming assets mid-frame would use a fence +
 * overlap instead (a later concern). Factored out of {@link Buffer} once a
 * second caller ({@link Texture}) needed the identical scaffolding.
 */
// Package-private: an internal helper, not part of jvre's public API.
final class Commands {

    private Commands() {}  // static utility; no instances

    /** What to record into the one-shot command buffer (between begin and end). */
    @FunctionalInterface
    public interface Recorder {
        void record(VkCommandBuffer cmd);
    }

    public static void oneShot(Device device, long commandPool, Recorder body) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            Vk.check(vkAllocateCommandBuffers(device.handle(), allocInfo, pCmd),
                    "Failed to allocate a one-shot command buffer");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device.handle());

            // ONE_TIME_SUBMIT: recorded, submitted once, thrown away -- the driver
            // may skip optimizing it for replay.
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            Vk.check(vkBeginCommandBuffer(cmd, beginInfo),
                    "Failed to begin a one-shot command buffer");

            body.record(cmd);  // the caller's actual commands

            Vk.check(vkEndCommandBuffer(cmd),
                    "Failed to record a one-shot command buffer");

            // Submit with no semaphores/fence and block the queue until it lands
            // (vkQueueWaitIdle doubles as a full memory barrier).
            VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmdInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
            cmdInfo.commandBuffer(cmd);

            VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2);
            submitInfo.pCommandBufferInfos(cmdInfo);

            Vk.check(vkQueueSubmit2(device.graphicsQueue(), submitInfo, VK_NULL_HANDLE),
                    "Failed to submit a one-shot command buffer");
            Vk.check(vkQueueWaitIdle(device.graphicsQueue()),
                    "Failed waiting for a one-shot command buffer");

            vkFreeCommandBuffers(device.handle(), commandPool, pCmd);
        }
    }
}
