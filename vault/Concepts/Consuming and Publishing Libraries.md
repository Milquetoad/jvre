# Consuming and Publishing Libraries (Gradle)

How another project would use jvre, and how the dependency "layers" work. Builds on [[Gradle]] and [[Build Tools]].

## Three ways to consume a library
1. **Published artifact** — jvre is packaged as a `.jar` and published to a repo; consumer adds it as a dependency:
   ```groovy
   repositories { mavenCentral() }
   dependencies { implementation 'io.github.pedergodal:jvre:0.1.0' }  // group:name:version
   ```
   Publish targets: **Maven Central** (public), **GitHub Packages**, a private repo, or **local** (`gradle publishToMavenLocal` → `~/.m2`, easiest for using your own lib on your own machine).
2. **Included build** (best while developing both at once) — consumer's `settings.gradle`: `includeBuild '../jvre'`. Gradle builds jvre from source and wires it in live; no publishing.
3. **Multi-project build** (one repo, many modules) — `include 'engine', 'examples'` + `implementation project(':engine')`. **Likely how we'll restructure jvre** (`:engine` library + `:examples` demos).

## "Gradle in several layers" — yes, and it's fine
Not Gradle nested/recursive. Each project builds independently; one project's **output artifact** is the next project's **input dependency**. The layers are the **dependency graph**, which Gradle flattens + resolves **transitively**:
```
their app → jvre → LWJGL → (LWJGL's deps)
```
Consumers don't list LWJGL manually — they get it transitively because jvre declares it. This is the whole point of a dependency system.

## `api` vs `implementation` (the key library-author concept)
When jvre depends on [[LWJGL]], how that dep "leaks" matters:

| Declaration | Consumer at runtime | Consumer compile classpath |
|---|---|---|
| `implementation` | yes | **no** (hidden) |
| `api` | yes | **yes** (exposed transitively) |

Rule: **if a dependency's type appears in your public API, it must be `api`.** Otherwise prefer `implementation` to hide it.

Ties to jvre's goal: jvre abstracts Vulkan behind our own classes → keep LWJGL as `implementation` where possible; only the bits that leak (some math/buffer types) become `api`. Requires the **`java-library`** plugin (which unlocks `api`), replacing the `application` plugin we currently use.

#concept #tooling
