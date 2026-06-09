# Vulkan Struct Conventions (`sType` / `pNext`)

Almost every Vulkan struct begins with two fields. They're the connective tissue of the whole API.

## `sType` — "structure type"
An enum tag identifying *what kind of struct this is*, e.g. `VK_STRUCTURE_TYPE_APPLICATION_INFO`. Feels redundant (the struct already is that type), but the driver reads raw C memory through a pointer ([[Off-Heap Memory]]) and uses `sType` to verify/dispatch. **You must set it every time.**

## `pNext` — "pointer to next"
Usually `null`. It's the official mechanism for **chaining extension structs** onto a base struct: point `pNext` at an extension's struct to form a linked list the driver walks. Lets extensions add fields without changing the base struct.

Example use: chain a `VkDebugUtilsMessengerCreateInfoEXT` into the [[Vulkan Instance|instance]] create-info's `pNext` to catch validation errors that occur *during* instance creation itself.

Once you recognize the `sType`/`pNext` rhythm, every Vulkan struct stops looking alien.

#vulkan #concept
