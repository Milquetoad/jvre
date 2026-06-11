package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkValidationFeaturesEXT;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTValidationFeatures.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * The Vulkan instance -- the root handle / loader entry point -- bundled with the
 * validation layer and debug messenger (the "safety net") when enabled.
 *
 * Infrastructure tier: owned and hidden. A user never constructs this; the
 * context/renderer does, once. Named after the Vulkan object it wraps (VkInstance).
 *
 * It pulls the required windowing extensions from GLFW
 * (glfwGetRequiredInstanceExtensions) -- presentation needs per-OS surface
 * extensions, and GLFW reports the right ones, which keeps us cross-platform.
 */
public class Instance {

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private final boolean validation;
    private final VkInstance handle;

    private long debugMessenger = VK_NULL_HANDLE;
    // Native callback kept as a field so we can free it at shutdown (off-heap).
    private VkDebugUtilsMessengerCallbackEXT debugCallback;

    public Instance(String appName, boolean enableValidation) {
        this.validation = enableValidation;

        if (!glfwVulkanSupported()) {
            throw new IllegalStateException("Vulkan is not supported by the loader");
        }
        // Two independent "supports 1.3" questions: the INSTANCE (loader/runtime)
        // version -- checked here -- and each GPU's own apiVersion, checked during
        // device selection. A new driver on an old runtime (or vice versa) can
        // genuinely differ, so both ends get verified.
        int loaderVersion = VK.getInstanceVersionSupported();
        if (loaderVersion < VK_API_VERSION_1_3) {
            throw new IllegalStateException("Vulkan 1.3 required, but the loader supports only "
                    + VK_VERSION_MAJOR(loaderVersion) + "." + VK_VERSION_MINOR(loaderVersion)
                    + " -- update the GPU driver / Vulkan runtime");
        }
        if (validation && !checkValidationLayerSupport()) {
            throw new RuntimeException(
                    "Validation layer requested but not available. Is the Vulkan SDK installed?");
        }

        try (MemoryStack stack = stackPush()) {
            // ---- App metadata ----
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8(appName));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8("jvre"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            // Require Vulkan 1.3: dynamic rendering + synchronization2 (our render
            // path) are core in 1.3. This is the MAX version we intend to use; the
            // loader was verified above, the chosen GPU during device selection.
            appInfo.apiVersion(VK_API_VERSION_1_3);

            // ---- Instance recipe ----
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);
            createInfo.ppEnabledExtensionNames(getRequiredExtensions(stack));

            if (validation) {
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));

                // Chain a messenger create-info into pNext so messages emitted
                // DURING vkCreateInstance / vkDestroyInstance are reported too.
                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo =
                        VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                populateDebugMessengerCreateInfo(debugCreateInfo);

                // Turn ON two opt-in check sets the layer does NOT run by default:
                //   - SYNCHRONIZATION: models every execution/memory dependency and
                //     reports actual HAZARDS (write-after-write, read-before-write),
                //     not just spec violations. We hand-roll our barriers now, so
                //     this is the net under exactly the code most likely to be
                //     subtly wrong.
                //   - BEST_PRACTICES: vendor-collected "valid but ill-advised"
                //     warnings (suspicious usage the core checks can't object to).
                // Config struct, not API: VkValidationFeaturesEXT is read by the
                // LAYER, which is why it only gets chained when validating.
                VkValidationFeaturesEXT validationFeatures =
                        VkValidationFeaturesEXT.calloc(stack);
                validationFeatures.sType(VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT);
                validationFeatures.pEnabledValidationFeatures(stack.ints(
                        VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT,
                        VK_VALIDATION_FEATURE_ENABLE_BEST_PRACTICES_EXT));

                // pNext chain: createInfo -> validationFeatures -> debugCreateInfo.
                validationFeatures.pNext(debugCreateInfo.address());
                createInfo.pNext(validationFeatures.address());
            } else {
                createInfo.ppEnabledLayerNames(null);
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            Vk.check(vkCreateInstance(createInfo, null, pInstance),
                    "Failed to create the Vulkan instance");
            handle = new VkInstance(pInstance.get(0), createInfo);
        }

        if (validation) {
            setupDebugMessenger();
        }
        System.out.println("Vulkan instance created successfully.");
    }

    /** The wrapped VkInstance -- for surface creation and physical-device enumeration. */
    public VkInstance handle() {
        return handle;
    }

    /** Destroy the messenger (if any), then the instance, then free the callback. */
    public void close() {
        if (validation && debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(handle, debugMessenger, null);
        }
        vkDestroyInstance(handle, null);
        if (debugCallback != null) {
            debugCallback.free();
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** Enumerate available instance layers and check our validation layer is among them. */
    private boolean checkValidationLayerSupport() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer layerCount = stack.ints(0);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer availableLayers =
                    VkLayerProperties.malloc(layerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            for (VkLayerProperties layer : availableLayers) {
                if (VALIDATION_LAYER.equals(layer.layerNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** GLFW's required extensions, plus VK_EXT_debug_utils when validating. */
    private PointerBuffer getRequiredExtensions(MemoryStack stack) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new RuntimeException("Failed to get the required GLFW instance extensions");
        }
        if (!validation) {
            return glfwExtensions;
        }
        // Make room for one more name: VK_EXT_debug_utils.
        PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);
        extensions.put(glfwExtensions);                                  // copy GLFW's names
        extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));   // add ours
        return extensions.rewind();
    }

    private void setupDebugMessenger() {
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT createInfo =
                    VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pMessenger = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateDebugUtilsMessengerEXT(handle, createInfo, null, pMessenger),
                    "Failed to set up the debug messenger");
            debugMessenger = pMessenger.get(0);
        }
    }

    /** Fill a messenger create-info: which message levels/types we want, and our callback. */
    private void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT createInfo) {
        if (debugCallback == null) {
            debugCallback = VkDebugUtilsMessengerCallbackEXT.create(Instance::onDebugMessage);
        }
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        // Only WARNING and ERROR: stays silent on success, loud on mistakes.
        createInfo.messageSeverity(
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
        createInfo.messageType(
                VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
        createInfo.pfnUserCallback(debugCallback);
    }

    /**
     * The callback Vulkan invokes for each message. Must return VK_FALSE
     * (VK_TRUE is reserved for layer-internal testing and aborts the call).
     */
    private static int onDebugMessage(int severity, int messageType, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT data =
                VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        String message = data.pMessageString();

        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            System.err.println("[Vulkan ERROR] " + message);
        } else {
            System.err.println("[Vulkan WARN]  " + message);
        }
        return VK_FALSE;
    }
}
