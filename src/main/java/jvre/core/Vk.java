package jvre.core;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Tiny shared helper for raw Vulkan calls.
 *
 * Vulkan reports failure through VkResult return codes, not exceptions, and a
 * call that "fails" by returning a code is otherwise easy to ignore by
 * accident. Every create/record/submit call in jvre funnels through
 * {@link #check} so a failure (a) always throws, and (b) always says WHICH
 * code came back -- "failed" without the VkResult is undebuggable.
 *
 * Calls whose non-SUCCESS codes are EXPECTED control flow (e.g. acquire and
 * present returning OUT_OF_DATE/SUBOPTIMAL on resize) must NOT go through
 * this -- they need real handling, not an exception.
 */
// Package-private: an internal helper, not part of jvre's public API.
final class Vk {

    private Vk() {}  // static utility; no instances

    /** Throw (including the VkResult code) unless {@code result} is VK_SUCCESS. */
    public static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException(what + " (VkResult " + result + ")");
        }
    }
}
