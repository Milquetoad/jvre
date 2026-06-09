# JNI (Java Native Interface)

The official mechanism for Java to call into **native** (C/C++) code (and back). The JVM can't directly invoke a C function; JNI is the bridge across that boundary.

[[LWJGL]] uses JNI under the hood: calling `VK10.vkCreateInstance(...)` in Java crosses the JNI bridge to invoke the real C `vkCreateInstance` inside `vulkan-1.dll`, then returns the result.

JNI handles the *calls*; the *data* side of the bridge (C structs, pointers) is handled with [[Off-Heap Memory]].

#concept
