package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

/**
 * The chosen GPU and our connection to it: physical-device SELECTION (the scoring
 * policy), the logical VkDevice, and the graphics + present queue handles.
 *
 * This is the head of the recreatable "device context" -- to switch GPUs or
 * rebuild after a resize you tear this (and everything below it) down and make a
 * new one, while Instance + Surface stay put. {@code rateDevice} is the
 * selection-policy seam (default: prefer discrete); a future flexible-selection
 * API plugs in there. {@code checkDeviceExtensionSupport} makes swapchain support
 * a hard requirement, since a GPU that can't present is useless to us.
 */
public class Device {

    // Device-level extensions we require. VK_KHR_swapchain lets us present to the
    // surface, so we REQUIRE it during selection and ENABLE it on the device.
    private static final String[] DEVICE_EXTENSIONS = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };

    private final VkPhysicalDevice physicalDevice;
    private final VkDevice handle;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final int graphicsFamily;
    private final int presentFamily;
    private final long allocator;  // VmaAllocator -- device-scoped, owns the memory blocks
    private final float maxAnisotropy;  // sampler anisotropy cap (1.0 = feature off/unavailable)

    public Device(Instance instance, Surface surface, String preferGpu) {
        this.physicalDevice = pickPhysicalDevice(instance, surface, preferGpu);

        // surface == null = HEADLESS: no window/swapchain, render only into targets
        // and read back. Then there is no present requirement -- present "is" the
        // graphics family (never used to present), so the rest of the code is
        // unchanged (queue set collapses to one family, EXCLUSIVE sharing).
        boolean headless = surface == null;
        QueueFamilyIndices indices = findQueueFamilies(physicalDevice, surface);
        this.graphicsFamily = indices.graphicsFamily;
        this.presentFamily = headless ? indices.graphicsFamily : indices.presentFamily;

        try (MemoryStack stack = stackPush()) {
            // Collapse graphics+present into the set of DISTINCT families to request
            // (Vulkan forbids listing the same family twice).
            Set<Integer> uniqueFamilies = new HashSet<>();
            uniqueFamilies.add(graphicsFamily);
            uniqueFamilies.add(presentFamily);

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos =
                    VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);
            FloatBuffer priority = stack.floats(1.0f);  // count of priorities = queues per family
            int idx = 0;
            for (int family : uniqueFamilies) {
                VkDeviceQueueCreateInfo qci = queueCreateInfos.get(idx++);
                qci.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                qci.queueFamilyIndex(family);
                qci.pQueuePriorities(priority);
            }

            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);
            // Anisotropic filtering is an OPTIONAL core feature (a device-feature
            // toggle, not a 1.3 promoted one) -- it must be ENABLED at device
            // creation before any sampler may request it, and the device advertises
            // a max. Query support, enable it if present, and remember the cap (1.0 =
            // "off / not available", a sane sampler value). Universal on desktop;
            // queried rather than assumed (the house rule, like the depth format).
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, supported);
            if (supported.samplerAnisotropy()) {
                deviceFeatures.samplerAnisotropy(true);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(physicalDevice, props);
                maxAnisotropy = props.limits().maxSamplerAnisotropy();
            } else {
                maxAnisotropy = 1.0f;
            }

            // Opt IN to the post-1.0 features we use. Features added after 1.0 are
            // enabled by CHAINING a feature struct into pNext rather than via
            // pEnabledFeatures. Instead of chaining one struct per feature
            // (VkPhysicalDeviceDynamicRenderingFeatures + ...Synchronization2Features
            // + ...), each core release ships an AGGREGATE struct holding all of its
            // promoted feature toggles -- the modern idiom is to chain that once.
            //   - dynamicRendering: our render path (no render pass/framebuffer).
            //   - synchronization2: the redesigned barrier/submit API we sync with.
            VkPhysicalDeviceVulkan13Features features13 =
                    VkPhysicalDeviceVulkan13Features.calloc(stack);
            features13.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
            features13.dynamicRendering(true);
            features13.synchronization2(true);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures);
            // Swapchain extension only when we have a surface to present to.
            createInfo.ppEnabledExtensionNames(asPointerBuffer(stack,
                    headless ? new String[0] : DEVICE_EXTENSIONS));
            createInfo.pNext(features13.address());  // chain the 1.3 features in
            // No ppEnabledLayerNames: device-level layers are legacy; current
            // validation requires enabledLayerCount == 0 (the instance layer covers
            // device calls).

            PointerBuffer pDevice = stack.mallocPointer(1);
            Vk.check(vkCreateDevice(physicalDevice, createInfo, null, pDevice),
                    "Failed to create the logical device");
            handle = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            // Queues are created with the device; we only retrieve handles. Index 0
            // = the first (and only) queue we requested from each family.
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(handle, graphicsFamily, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), handle);
            vkGetDeviceQueue(handle, presentFamily, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), handle);

            // ---- The VMA allocator: real-engine memory management ----
            // VMA sub-allocates many buffers/images out of FEW big VkDeviceMemory
            // blocks -- the pattern drivers expect (maxMemoryAllocationCount may
            // be as low as 4096, and vkAllocateMemory is slow). It also takes
            // over memory-TYPE selection: callers declare INTENT (auto / host
            // access) instead of hunting type bits. Replaces jvre's deliberate
            // one-allocation-per-resource learning scaffolding.
            //
            // VMA is a C++ library that itself calls Vulkan; LWJGL loads Vulkan
            // dynamically, so we must hand VMA the function pointers
            // (VmaVulkanFunctions.set resolves them from instance + device).
            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack)
                    .set(instance.handle(), handle);

            VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack);
            allocatorInfo.instance(instance.handle());
            allocatorInfo.physicalDevice(physicalDevice);
            allocatorInfo.device(handle);
            allocatorInfo.pVulkanFunctions(vulkanFunctions);
            // Tell VMA which CORE Vulkan it may rely on (we verified 1.3 in
            // device selection, so this is honest).
            allocatorInfo.vulkanApiVersion(VK_API_VERSION_1_3);

            PointerBuffer pAllocator = stack.mallocPointer(1);
            Vk.check(vmaCreateAllocator(allocatorInfo, pAllocator),
                    "Failed to create the VMA allocator");
            allocator = pAllocator.get(0);
        }

        boolean shared = graphicsFamily == presentFamily;
        System.out.println("Logical device created; graphics + present queues retrieved"
                + (shared ? " (shared family)." : " (separate families)."));
        System.out.println("VMA allocator created (Vulkan 1.3).");
    }

    public VkPhysicalDevice physicalDevice() { return physicalDevice; }
    public VkDevice handle()                 { return handle; }
    public VkQueue graphicsQueue()           { return graphicsQueue; }
    public VkQueue presentQueue()            { return presentQueue; }
    public int graphicsFamily()              { return graphicsFamily; }
    public int presentFamily()               { return presentFamily; }
    public long allocator()                  { return allocator; }  // VmaAllocator
    /** Max sampler anisotropy the device supports (1.0 = the feature is off/unavailable);
     *  a sampler clamps its requested level to this. */
    float maxAnisotropy()                    { return maxAnisotropy; }

    // (The hand-rolled findMemoryType -- the memory-type hunt both Buffer and
    // Texture once used -- retired when VMA took over type selection. The
    // concept is preserved in the vault's Vertex Buffers / Textures notes and
    // in git history.)

    public void close() {
        // The allocator holds VkDeviceMemory blocks from this device -- it must
        // go first (child before parent, as ever). If any vmaCreate*'d resource
        // is still alive here, VMA asserts loudly -- a free leak detector.
        vmaDestroyAllocator(allocator);
        vkDestroyDevice(handle, null);
    }

    // ------------------------------------------------------------------
    // selection
    // ------------------------------------------------------------------

    private VkPhysicalDevice pickPhysicalDevice(Instance instance, Surface surface, String preferGpu) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer deviceCount = stack.ints(0);
            vkEnumeratePhysicalDevices(instance.handle(), deviceCount, null);
            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("No GPUs with Vulkan support found");
            }
            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance.handle(), deviceCount, devices);

            // Walk every GPU; keep the highest-scoring SUITABLE one. Headless drops
            // the present + swapchain requirements (no surface to present to).
            boolean needPresent = surface != null;
            VkPhysicalDevice best = null;
            int bestScore = -1;
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance.handle());
                QueueFamilyIndices indices = findQueueFamilies(candidate, surface);
                // Suitable = a graphics queue, (present + swapchain unless headless),
                // AND Vulkan 1.3 with the features our render path actually enables.
                if (!indices.isComplete(needPresent)
                        || !checkDeviceExtensionSupport(candidate, needPresent, stack)
                        || !checkApiAndFeatureSupport(candidate, stack)) {
                    continue;
                }
                int score = rateDevice(candidate, stack, preferGpu);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null) {
                throw new RuntimeException("No suitable GPU found (need graphics"
                        + (needPresent ? " + present + swapchain" : "")
                        + " + Vulkan 1.3 with dynamicRendering/synchronization2)");
            }

            // If an override was requested but the winner doesn't match it, no
            // suitable device did -- say so (the user asked for a GPU we couldn't honor).
            if (preferGpu != null) {
                VkPhysicalDeviceProperties chosen = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(best, chosen);
                if (!chosen.deviceNameString().toLowerCase().contains(preferGpu.toLowerCase())) {
                    System.out.println("Note: preferred GPU '" + preferGpu
                            + "' not found among suitable devices; using the best-scored.");
                }
            }

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(best, props);
            // Fingerprint lines (captured to the [[Diagnostics]] log): the exact
            // GPU, its type, the core version it speaks, and the driver -- the
            // first things any Ring 2 bug report needs. driverVersion is
            // vendor-ENCODED (not the VK_VERSION layout), so it's logged raw;
            // vendor/device IDs pin the exact silicon regardless of name string.
            int api = props.apiVersion();
            System.out.println("Picked GPU: " + props.deviceNameString()
                    + " [" + deviceTypeName(props.deviceType()) + "] (score " + bestScore + ")");
            System.out.printf("  vendor=0x%04X device=0x%04X  apiVersion=%d.%d.%d  driverVersion=0x%08X (vendor-encoded)%n",
                    props.vendorID(), props.deviceID(),
                    VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api), VK_VERSION_PATCH(api),
                    props.driverVersion());
            return best;
        }
    }

    /**
     * Default selection policy: higher = better. Discrete GPUs win big over
     * integrated; maxImageDimension2D is a gentle tiebreak. This is the seam for
     * the future flexible-selection work (explicit override + runtime switching).
     */
    private int rateDevice(VkPhysicalDevice device, MemoryStack stack, String preferGpu) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device, props);

        int score = 0;
        // Explicit override wins big: a name-substring match dwarfs the type/limit
        // terms, so a requested GPU beats the default discrete-vs-integrated policy.
        if (preferGpu != null
                && props.deviceNameString().toLowerCase().contains(preferGpu.toLowerCase())) {
            score += 1_000_000;
        }
        if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 1000;  // strongly prefer the dedicated GPU (e.g. the RTX 4090)
        }
        score += props.limits().maxImageDimension2D();
        return score;
    }

    /** Human-readable VK_PHYSICAL_DEVICE_TYPE_* -- for the diagnostics fingerprint. */
    private static String deviceTypeName(int type) {
        switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual";
            case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "cpu";
            default:                                     return "other";
        }
    }

    /**
     * Does this GPU support what we actually USE: Vulkan 1.3, plus the
     * dynamicRendering and synchronization2 feature bits? Enabling a feature the
     * device lacks is invalid (it merely happened to work on our dev GPUs), so
     * suitability must verify the exact bits the constructor turns on.
     *
     * Note the symmetry: vkGetPhysicalDeviceFeatures2 QUERIES through the same
     * pNext chain we later ENABLE through -- chain an empty feature struct in,
     * and the driver fills in its supported flags.
     */
    private boolean checkApiAndFeatureSupport(VkPhysicalDevice device, MemoryStack stack) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device, props);
        // apiVersion = the highest CORE version this device's driver speaks. Gate
        // on it first -- only then is it valid to ask about 1.3 feature structs.
        if (props.apiVersion() < VK_API_VERSION_1_3) {
            return false;
        }

        VkPhysicalDeviceVulkan13Features features13 =
                VkPhysicalDeviceVulkan13Features.calloc(stack);
        features13.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);

        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
        features2.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
        features2.pNext(features13.address());

        vkGetPhysicalDeviceFeatures2(device, features2);
        return features13.dynamicRendering() && features13.synchronization2();
    }

    /** Does this GPU support every device extension we require (per-GPU enumeration)?
     *  Headless requires none ({@code needSwapchain == false}); the windowed path
     *  requires VK_KHR_swapchain. */
    private boolean checkDeviceExtensionSupport(VkPhysicalDevice device, boolean needSwapchain,
                                                MemoryStack stack) {
        if (!needSwapchain) {
            return true;   // headless: no device extensions required
        }
        IntBuffer extCount = stack.ints(0);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extCount, null);

        // Allocate the count-sized properties buffer on the NATIVE HEAP, not the
        // shared MemoryStack. A driver can report HUNDREDS of device extensions, each
        // a VkExtensionProperties of ~260 bytes (a 256-char name + version); a few
        // hundred overflows LWJGL's default 64 KB per-thread stack -> OutOfMemoryError
        // ("Out of stack space") during device selection, on extension-rich drivers.
        // The heap has no such limit. (This is why callers used to need
        // Configuration.STACK_SIZE bumped -- now jvre handles it.) Freed below.
        VkExtensionProperties.Buffer available =
                VkExtensionProperties.malloc(extCount.get(0));
        try {
            vkEnumerateDeviceExtensionProperties(device, (String) null, extCount, available);

            Set<String> required = new HashSet<>(Arrays.asList(DEVICE_EXTENSIONS));
            for (VkExtensionProperties ext : available) {
                required.remove(ext.extensionNameString());
            }
            return required.isEmpty();
        } finally {
            available.free();
        }
    }

    /**
     * Find a queue family that supports graphics and (unless headless) one that can
     * present to our surface (they may coincide). Present support is surface-specific,
     * so it is QUERIED per family; a null surface (headless) skips it entirely.
     */
    private QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, Surface surface) {
        boolean needPresent = surface != null;
        try (MemoryStack stack = stackPush()) {
            QueueFamilyIndices indices = new QueueFamilyIndices();

            IntBuffer count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);

            VkQueueFamilyProperties.Buffer families =
                    VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

            IntBuffer presentSupport = stack.ints(VK_FALSE);
            for (int i = 0; i < families.capacity(); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i;
                }
                if (needPresent) {
                    vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface.handle(), presentSupport);
                    if (presentSupport.get(0) == VK_TRUE) {
                        indices.presentFamily = i;
                    }
                }
                if (indices.isComplete(needPresent)) {
                    break;
                }
            }
            return indices;
        }
    }

    /** Pack a String[] into a PointerBuffer of UTF-8 names for Vulkan. */
    private PointerBuffer asPointerBuffer(MemoryStack stack, String[] strings) {
        PointerBuffer buffer = stack.mallocPointer(strings.length);
        for (String s : strings) {
            buffer.put(stack.UTF8(s));
        }
        return buffer.rewind();
    }

    /**
     * Queue family indices we care about. Integer (not int) so null = "not found
     * yet" -- index 0 is a valid family, so -1 isn't a safe sentinel.
     */
    private static class QueueFamilyIndices {
        Integer graphicsFamily;
        Integer presentFamily;

        /** Complete = a graphics family, plus a present family when one is needed
         *  (the windowed path; headless needs only graphics). */
        boolean isComplete(boolean needPresent) {
            return graphicsFamily != null && (!needPresent || presentFamily != null);
        }
    }
}
