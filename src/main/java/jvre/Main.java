package jvre;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Step 2: instance + validation layer + debug messenger (the "safety net").
 *
 * Vulkan is silent on misuse. The validation layer checks our every call; the
 * debug messenger is the callback that delivers those check results to us. With
 * this in place, mistakes show up as readable messages instead of crashes or
 * (worse) silent wrong behavior.
 */
public class Main {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final CharSequence TITLE = "jvre - logical device";

    // Flip to false to build a "release" run with no validation overhead.
    private static final boolean ENABLE_VALIDATION = true;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    // Device-level extensions we require. VK_KHR_swapchain is what lets us present
    // rendered images to the surface (the next milestone), so we both REQUIRE it
    // during GPU selection and ENABLE it when creating the logical device.
    private static final String[] DEVICE_EXTENSIONS = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };

    private long window;
    private VkInstance instance;

    // The window<->Vulkan bridge. A VkSurfaceKHR is an opaque handle (a long),
    // owned by the instance. Created from the GLFW window; later steps ask the
    // GPU "can you present to THIS surface?" and build the swapchain for it.
    private long surface = VK_NULL_HANDLE;

    // The GPU we chose to render with. A "physical device" is a handle to a real
    // GPU; it is OWNED BY the instance and is NOT destroyed (you don't create it,
    // you just select it). The logical device (next step) is what we create.
    private VkPhysicalDevice physicalDevice = null;

    // The logical device: our actual CONNECTION to the chosen GPU. Unlike the
    // physical device, WE create this (and must destroy it). All real work --
    // swapchains, pipelines, buffers, command submission -- goes through the
    // logical device, never the physical one.
    private VkDevice device;

    // Handles to the specific queues we asked the device to create; this is where
    // we submit work. They may be the SAME underlying queue if one family does both.
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;

    // Handle to the debug messenger object (0 = none).
    private long debugMessenger = VK_NULL_HANDLE;
    // The native callback function. We keep a reference so we can free it at
    // shutdown (it lives in off-heap memory, like everything else native).
    private VkDebugUtilsMessengerCallbackEXT debugCallback;

    public static void main(String[] args) {
        // See the MemoryStack gotcha note: this machine's GPUs expose enough
        // extensions to overflow the default 64 KB per-thread stack.
        Configuration.STACK_SIZE.set(512);

        new Main().run();
    }

    public void run() {
        initWindow();
        initVulkan();
        mainLoop();
        cleanup();
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------
    private void initWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);  // no OpenGL context
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);    // no swapchain yet

        window = glfwCreateWindow(WIDTH, HEIGHT, TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }
    }

    // ------------------------------------------------------------------
    // Vulkan
    // ------------------------------------------------------------------
    private void initVulkan() {
        createInstance();
        setupDebugMessenger();
        createSurface();
        pickPhysicalDevice();
        createLogicalDevice();
    }

    private void createInstance() {
        if (!glfwVulkanSupported()) {
            throw new IllegalStateException("Vulkan is not supported by the loader");
        }
        if (ENABLE_VALIDATION && !checkValidationLayerSupport()) {
            throw new RuntimeException(
                    "Validation layer requested but not available. Is the Vulkan SDK installed?");
        }

        try (MemoryStack stack = stackPush()) {
            // ---- App metadata ----
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8("jvre demo"));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8("jvre"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.apiVersion(VK_API_VERSION_1_0);

            // ---- Instance recipe ----
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);
            createInfo.ppEnabledExtensionNames(getRequiredExtensions(stack));

            if (ENABLE_VALIDATION) {
                // Enable the validation layer.
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));

                // Chain a debug-messenger create-info into pNext so that messages
                // emitted DURING vkCreateInstance / vkDestroyInstance are reported.
                // (The standalone messenger below only covers the time in between.)
                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo =
                        VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            } else {
                createInfo.ppEnabledLayerNames(null);
            }

            // ---- Create ----
            PointerBuffer pInstance = stack.mallocPointer(1);
            if (vkCreateInstance(createInfo, null, pInstance) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the Vulkan instance");
            }
            instance = new VkInstance(pInstance.get(0), createInfo);
        }

        System.out.println("Vulkan instance created successfully.");
    }

    /** Enumerate available instance layers and check our validation layer is among them. */
    private boolean checkValidationLayerSupport() {
        try (MemoryStack stack = stackPush()) {
            // Classic two-call idiom: first call asks "how many?", second fills the buffer.
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

    /** GLFW's required extensions, plus the debug-utils extension when validating. */
    private PointerBuffer getRequiredExtensions(MemoryStack stack) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new RuntimeException("Failed to get the required GLFW instance extensions");
        }
        if (!ENABLE_VALIDATION) {
            return glfwExtensions;
        }
        // Make room for one more name: VK_EXT_debug_utils.
        PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);
        extensions.put(glfwExtensions);                                  // copy GLFW's names
        extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));   // add ours
        return extensions.rewind();
    }

    private void setupDebugMessenger() {
        if (!ENABLE_VALIDATION) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT createInfo =
                    VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pMessenger = stack.longs(VK_NULL_HANDLE);
            if (vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pMessenger) != VK_SUCCESS) {
                throw new RuntimeException("Failed to set up the debug messenger");
            }
            debugMessenger = pMessenger.get(0);
        }
    }

    /** Fill a messenger create-info: which message levels/types we want, and our callback. */
    private void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT createInfo) {
        if (debugCallback == null) {
            debugCallback = VkDebugUtilsMessengerCallbackEXT.create(Main::onDebugMessage);
        }
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        // Only WARNING and ERROR: stays silent on success, loud on mistakes.
        // (Add ..._VERBOSE_BIT_EXT / ..._INFO_BIT_EXT to hear everything.)
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

    // ------------------------------------------------------------------
    // Surface — the window <-> Vulkan bridge
    // ------------------------------------------------------------------
    private void createSurface() {
        try (MemoryStack stack = stackPush()) {
            // Output param: glfwCreateWindowSurface writes the new handle here.
            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);

            // GLFW picks the right platform call (vkCreateWin32SurfaceKHR on
            // Windows) using the native handle of the window it created.
            // Note it returns a Vulkan result code (an int), not the handle.
            int result = glfwCreateWindowSurface(instance, window, null, pSurface);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the window surface: " + result);
            }
            surface = pSurface.get(0);
        }
        System.out.println("Window surface created.");
    }

    // ------------------------------------------------------------------
    // Physical device (GPU) selection
    // ------------------------------------------------------------------

    /**
     * The queue family indices we care about. Integer (not int) so null means
     * "not found yet" — index 0 is a perfectly valid family, so we can't use -1
     * as a sentinel without being careful. isComplete() = we found everything.
     *
     * graphics and present MAY be the same family; we allow that.
     */
    private static class QueueFamilyIndices {
        Integer graphicsFamily;
        Integer presentFamily;

        boolean isComplete() {
            return graphicsFamily != null && presentFamily != null;
        }
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            // Two-call idiom again: how many GPUs? then list them.
            IntBuffer deviceCount = stack.ints(0);
            vkEnumeratePhysicalDevices(instance, deviceCount, null);
            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("No GPUs with Vulkan support found");
            }

            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, devices);

            // Walk every GPU; keep the highest-scoring SUITABLE one.
            VkPhysicalDevice best = null;
            int bestScore = -1;
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);

                QueueFamilyIndices indices = findQueueFamilies(device, stack);
                // Suitable = has the queues we need AND supports the device
                // extensions we require (swapchain). Both are hard requirements.
                if (!indices.isComplete() || !checkDeviceExtensionSupport(device, stack)) {
                    continue;  // missing graphics/present or swapchain -> unusable
                }

                int score = rateDevice(device, stack);
                if (score > bestScore) {
                    bestScore = score;
                    best = device;
                }
            }

            if (best == null) {
                throw new RuntimeException(
                        "No suitable GPU found (need graphics + present + swapchain)");
            }
            physicalDevice = best;

            // Report what we picked (and re-read its name for the log).
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            System.out.println("Picked GPU: " + props.deviceNameString()
                    + " (score " + bestScore + ")");
        }
    }

    /**
     * Find, for one GPU, a queue family that supports graphics and one that can
     * present to our surface. They may coincide. Stops early once complete.
     */
    private QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, MemoryStack stack) {
        QueueFamilyIndices indices = new QueueFamilyIndices();

        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);

        VkQueueFamilyProperties.Buffer families =
                VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

        IntBuffer presentSupport = stack.ints(VK_FALSE);
        for (int i = 0; i < families.capacity(); i++) {
            // Graphics is a simple capability flag on the family.
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                indices.graphicsFamily = i;
            }
            // Present support is surface-specific -> must be QUERIED, not flagged.
            vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport);
            if (presentSupport.get(0) == VK_TRUE) {
                indices.presentFamily = i;
            }
            if (indices.isComplete()) {
                break;
            }
        }
        return indices;
    }

    /**
     * Default selection policy: higher = better. Discrete GPUs win big over
     * integrated. This is the seam for the future flexible-selection work
     * (explicit override + runtime switching) — see the vault design note.
     */
    private int rateDevice(VkPhysicalDevice device, MemoryStack stack) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device, props);

        int score = 0;
        if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 1000;  // strongly prefer the dedicated GPU (e.g. the RTX 4090)
        }
        // maxImageDimension2D is a rough proxy for "how beefy" — a gentle tiebreak.
        score += props.limits().maxImageDimension2D();
        return score;
    }

    /**
     * Does this GPU support every device extension we require? Same two-call
     * enumeration idiom as the instance layers, but at the DEVICE level --
     * extensions live on a specific GPU, so we enumerate per physical device.
     */
    private boolean checkDeviceExtensionSupport(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer extCount = stack.ints(0);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extCount, null);

        VkExtensionProperties.Buffer available =
                VkExtensionProperties.malloc(extCount.get(0), stack);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extCount, available);

        // Start with everything we need; cross off what the GPU offers. Empty = all met.
        Set<String> required = new HashSet<>(Arrays.asList(DEVICE_EXTENSIONS));
        for (VkExtensionProperties ext : available) {
            required.remove(ext.extensionNameString());
        }
        return required.isEmpty();
    }

    // ------------------------------------------------------------------
    // Logical device (VkDevice) + queues
    // ------------------------------------------------------------------

    /**
     * Create the logical device -- our handle for actually talking to the chosen
     * GPU -- and pull out the queue handles we'll submit work to.
     *
     * The recipe has three parts: (1) WHICH QUEUES to create (one from the
     * graphics family, one from the present family -- deduped, since they may be
     * the same family and Vulkan forbids listing it twice); (2) WHICH FEATURES to
     * enable (none yet); (3) WHICH DEVICE EXTENSIONS to enable (VK_KHR_swapchain).
     */
    private void createLogicalDevice() {
        try (MemoryStack stack = stackPush()) {
            QueueFamilyIndices indices = findQueueFamilies(physicalDevice, stack);

            // Collapse graphics+present into the set of DISTINCT families to request.
            Set<Integer> uniqueFamilies = new HashSet<>();
            uniqueFamilies.add(indices.graphicsFamily);
            uniqueFamilies.add(indices.presentFamily);

            // One create-info per distinct family; each asks for a single queue.
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos =
                    VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);

            // Priority in [0,1] hints scheduling when queues compete. With one queue
            // each it's moot, but the field is required. The COUNT of priorities is
            // how Vulkan infers how many queues we want from the family (here: 1).
            FloatBuffer priority = stack.floats(1.0f);

            int idx = 0;
            for (int family : uniqueFamilies) {
                VkDeviceQueueCreateInfo qci = queueCreateInfos.get(idx++);
                qci.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                qci.queueFamilyIndex(family);
                qci.pQueuePriorities(priority);
            }

            // No special device features needed yet (geometry shaders, aniso, ...).
            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures);
            createInfo.ppEnabledExtensionNames(asPointerBuffer(stack, DEVICE_EXTENSIONS));

            // NOTE: we deliberately leave ppEnabledLayerNames UNSET (count 0).
            // Device-level layers are a Vulkan 1.0 legacy concept that never worked;
            // the INSTANCE validation layer already covers device calls. Current
            // validation requires enabledLayerCount == 0 and errors otherwise
            // (VUID-VkDeviceCreateInfo-enabledLayerCount-12384).

            PointerBuffer pDevice = stack.mallocPointer(1);
            if (vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the logical device");
            }
            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            // Queues are CREATED with the device; we only RETRIEVE handles to them.
            // Index 0 = the first (and only) queue we requested from each family.
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, indices.graphicsFamily, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);
            vkGetDeviceQueue(device, indices.presentFamily, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);

            boolean shared = indices.graphicsFamily.equals(indices.presentFamily);
            System.out.println("Logical device created; graphics + present queues retrieved"
                    + (shared ? " (shared family)." : " (separate families)."));
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

    // ------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------
    private void mainLoop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
        }
    }

    // ------------------------------------------------------------------
    // Cleanup — reverse order of creation.
    // ------------------------------------------------------------------
    private void cleanup() {
        // Reverse order of creation. The logical device is the most dependent
        // object (everything device-level hangs off it), so it is torn down first.
        if (device != null) {
            vkDestroyDevice(device, null);
        }
        if (ENABLE_VALIDATION && debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        // Surface is owned by the instance -> destroy it before the instance.
        if (surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance, surface, null);
        }
        vkDestroyInstance(instance, null);

        // Free the native callback AFTER the instance (which referenced it) is gone.
        if (debugCallback != null) {
            debugCallback.free();
        }

        glfwDestroyWindow(window);
        glfwTerminate();

        System.out.println("Cleaned up. Bye.");
    }
}
