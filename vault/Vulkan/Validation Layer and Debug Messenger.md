# Validation Layer & Debug Messenger

The **safety net.** Vulkan is silent on misuse; the validation layer checks every call, and the debug messenger is the callback that delivers those results to us. **Always develop with this on** (see [[Vulkan Overview]]). In `jvre.Main`.

## The pieces (3)
1. **Enable the layer** `VK_LAYER_KHRONOS_validation` in the instance's `ppEnabledLayerNames` — but only after confirming it's available (`checkValidationLayerSupport`).
2. **Add the extension** `VK_EXT_debug_utils` to the instance extensions (it's what *lets* messages be delivered). Appended to GLFW's required list.
3. **Register a callback** via a debug messenger.

## New patterns learned here
### The two-call enumeration idiom (everywhere in Vulkan)
```java
IntBuffer count = stack.ints(0);
vkEnumerateInstanceLayerProperties(count, null);                  // (1) how many?
VkLayerProperties.Buffer buf = VkLayerProperties.malloc(count.get(0), stack);
vkEnumerateInstanceLayerProperties(count, buf);                   // (2) fill it
```
First call (null data ptr) writes the count; second fills a buffer of that size. Used for layers, devices, surface formats, swapchain images — all lists.

### A Java method as a native function pointer (JNI in reverse)
```java
debugCallback = VkDebugUtilsMessengerCallbackEXT.create(Main::onDebugMessage);
```
Vulkan (C) calls *back into Java*. LWJGL wraps our static method in a native trampoline. It lives off-heap → must `.free()` at shutdown. Callback must return `VK_FALSE`. Read the message via `VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData).pMessageString()`.

### Severity/type filters
`messageSeverity = WARNING | ERROR` → silent on success, loud on mistakes. (Add VERBOSE/INFO bits to hear everything.) `messageType = GENERAL | VALIDATION | PERFORMANCE`.

### Two messengers (the `pNext` trick)
- **Standalone** messenger (`vkCreateDebugUtilsMessengerEXT`) — active for the program's life, but created *after* / destroyed *before* the instance, so it can't see `vkCreateInstance`/`vkDestroyInstance` themselves.
- **Temporary** one chained into the instance create-info's `pNext` ([[Vulkan Struct Conventions]]) — covers those two blind-spot moments.

## Demo we ran (proof it works)
Deliberately skipped destroying the messenger before the instance → validation printed:
```
[Vulkan ERROR] vkDestroyInstance(): VkInstance ... has 1 leaked objects ...
VkDebugUtilsMessengerEXT ...  (VUID-vkDestroyInstance-instance-00629, + spec link)
```
Printed **twice** = both messengers (standalone + pNext) received it → confirms the pNext trick. An *invisible* bug in raw Vulkan became a precise, spec-cited message.

## Cleanup order
`vkDestroyDebugUtilsMessengerEXT` (child) → `vkDestroyInstance` (parent) → `debugCallback.free()` (after the instance that referenced it is gone).

#vulkan #validation #concept
