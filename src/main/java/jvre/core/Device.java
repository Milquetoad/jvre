package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
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

    public Device(Instance instance, Surface surface) {
        this.physicalDevice = pickPhysicalDevice(instance, surface);

        QueueFamilyIndices indices = findQueueFamilies(physicalDevice, surface);
        this.graphicsFamily = indices.graphicsFamily;
        this.presentFamily = indices.presentFamily;

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
            createInfo.ppEnabledExtensionNames(asPointerBuffer(stack, DEVICE_EXTENSIONS));
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
        }

        boolean shared = graphicsFamily == presentFamily;
        System.out.println("Logical device created; graphics + present queues retrieved"
                + (shared ? " (shared family)." : " (separate families)."));
    }

    public VkPhysicalDevice physicalDevice() { return physicalDevice; }
    public VkDevice handle()                 { return handle; }
    public VkQueue graphicsQueue()           { return graphicsQueue; }
    public VkQueue presentQueue()            { return presentQueue; }
    public int graphicsFamily()              { return graphicsFamily; }
    public int presentFamily()               { return presentFamily; }

    /**
     * The memory-type hunt -- owned HERE because the PHYSICAL DEVICE is what
     * advertises the GPU's table of memory TYPES (each pointing into a HEAP --
     * e.g. 24 GB of VRAM, or system RAM). Both {@link Buffer} and {@link Texture}
     * need it: given the resource's allowed-types bitmask (from
     * vkGet{Buffer,Image}MemoryRequirements) and the PROPERTIES we require,
     * return the index of a memory type that satisfies BOTH:
     *   - the resource allows it: bit {@code i} set in {@code typeFilter}, AND
     *   - it has every property asked for: HOST_VISIBLE (CPU can map it),
     *     HOST_COHERENT (no manual flushes), DEVICE_LOCAL (in VRAM), ...
     * On discrete GPUs HOST_VISIBLE and DEVICE_LOCAL are usually DIFFERENT types
     * -- that split is exactly why staging uploads exist.
     */
    public int findMemoryType(int typeFilter, int required) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps =
                    VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);

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

    public void close() {
        vkDestroyDevice(handle, null);
    }

    // ------------------------------------------------------------------
    // selection
    // ------------------------------------------------------------------

    private VkPhysicalDevice pickPhysicalDevice(Instance instance, Surface surface) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer deviceCount = stack.ints(0);
            vkEnumeratePhysicalDevices(instance.handle(), deviceCount, null);
            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("No GPUs with Vulkan support found");
            }
            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance.handle(), deviceCount, devices);

            // Walk every GPU; keep the highest-scoring SUITABLE one.
            VkPhysicalDevice best = null;
            int bestScore = -1;
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance.handle());
                QueueFamilyIndices indices = findQueueFamilies(candidate, surface);
                // Suitable = graphics + present queues, swapchain support, AND the
                // Vulkan 1.3 API + features our render path actually enables.
                if (!indices.isComplete()
                        || !checkDeviceExtensionSupport(candidate, stack)
                        || !checkApiAndFeatureSupport(candidate, stack)) {
                    continue;
                }
                int score = rateDevice(candidate, stack);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null) {
                throw new RuntimeException("No suitable GPU found (need graphics + present "
                        + "+ swapchain + Vulkan 1.3 with dynamicRendering/synchronization2)");
            }

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(best, props);
            System.out.println("Picked GPU: " + props.deviceNameString()
                    + " (score " + bestScore + ")");
            return best;
        }
    }

    /**
     * Default selection policy: higher = better. Discrete GPUs win big over
     * integrated; maxImageDimension2D is a gentle tiebreak. This is the seam for
     * the future flexible-selection work (explicit override + runtime switching).
     */
    private int rateDevice(VkPhysicalDevice device, MemoryStack stack) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device, props);

        int score = 0;
        if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 1000;  // strongly prefer the dedicated GPU (e.g. the RTX 4090)
        }
        score += props.limits().maxImageDimension2D();
        return score;
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

    /** Does this GPU support every device extension we require (per-GPU enumeration)? */
    private boolean checkDeviceExtensionSupport(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer extCount = stack.ints(0);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extCount, null);

        VkExtensionProperties.Buffer available =
                VkExtensionProperties.malloc(extCount.get(0), stack);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extCount, available);

        Set<String> required = new HashSet<>(Arrays.asList(DEVICE_EXTENSIONS));
        for (VkExtensionProperties ext : available) {
            required.remove(ext.extensionNameString());
        }
        return required.isEmpty();
    }

    /**
     * Find a queue family that supports graphics and one that can present to our
     * surface (they may coincide). Present support is surface-specific, so it is
     * QUERIED per family, not read from a flag.
     */
    private QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, Surface surface) {
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
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface.handle(), presentSupport);
                if (presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i;
                }
                if (indices.isComplete()) {
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

        boolean isComplete() {
            return graphicsFamily != null && presentFamily != null;
        }
    }
}
