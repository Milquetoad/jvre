# Gradle

The [[Build Tools|build tool]] for jvre. Project described in `build.gradle` (a Groovy *script* — real code, so we can add custom logic like "compile all shaders to SPIR-V before building").

## Our `build.gradle`, annotated
- `plugins { id 'application' }` — this is a runnable app; gives us a `run` task.
- `repositories { mavenCentral() }` — where to fetch dependencies from.
- `dependencies { ... }` — each entry is a **coordinate** `group:name`, e.g. `org.lwjgl:lwjgl-glfw`. Gradle downloads each + its deps and assembles the classpath.
  - `implementation` = needed to **compile**.
  - `runtimeOnly` = needed only to **run** (the native `.dll` jars — we never reference them in code).
- `application { mainClass = 'jvre.Main' }` — entry point.

`.\gradlew.bat run` → resolve coordinates → download what's missing → compile → launch `java` with the full classpath + native lib path.

## vs Maven
See [[Build Tools]]. Version pinning + no-install reproducibility: [[Gradle Wrapper]].

#concept #tooling
