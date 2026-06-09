# Creating a Gradle Project from Scratch

Two routes. Builds on [[Gradle]] and [[Gradle Wrapper]].

## Route A — the wizard: `gradle init`
Run `gradle init` in an empty folder. It asks: project type (`java-application` / `java-library`), language, DSL (Groovy `build.gradle` vs Kotlin `.kts`), test framework, name. It generates `settings.gradle`, `build.gradle`, the `src/main/java` layout, **and the wrapper**. Afterward you only use `.\gradlew.bat`.

## Route B — by hand (3 things + a src folder)
**1. `settings.gradle`** — names the build (+ lists modules if multi-project):
```groovy
rootProject.name = 'myproject'
```
**2. `build.gradle`** — library version:
```groovy
plugins { id 'java-library' }     // app: id 'application' + application { mainClass = '...' }
repositories { mavenCentral() }
dependencies {
    api 'group:exposed:1.0'           // leaks to consumers
    implementation 'group:internal:1.0'
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
```
**3. Source layout** (convention over configuration — Gradle assumes it, zero config needed):
```
src/main/java/        production code
src/main/resources/   bundled non-code (shaders, images)
src/test/java/        tests
```

**Then generate the wrapper once:**
```
gradle wrapper --gradle-version 8.10.2
```
From then on: `.\gradlew.bat build` / `run`. No global Gradle needed.

That's the whole thing. Everything else is dependencies + occasional custom tasks (e.g. our future shader→SPIR-V task, see [[Shaders - GLSL and SPIR-V]]).

#concept #tooling
