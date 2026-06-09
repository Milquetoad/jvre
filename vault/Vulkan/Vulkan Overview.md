# Vulkan Overview

A low-level, **explicit** graphics + compute API. "Explicit" means *we* create and wire every object and manage all memory/synchronization ourselves — verbose, but we understand and control everything (the whole point of jvre).

## Consequences to internalize
- Vulkan is **silent** on misuse → rely on **validation layers** (from the SDK) to surface errors in plain English. Always develop with them on.
- Vulkan has **no GC** for its objects → every created object is explicitly destroyed, in reverse order, at shutdown.
- Accessed from Java via [[LWJGL]]; structs live in [[Off-Heap Memory]], usually built on the [[MemoryStack]].

## Where we're headed
First goal: [[Roadmap - Clear to Color]], starting with the [[Vulkan Instance]].

#vulkan #moc
