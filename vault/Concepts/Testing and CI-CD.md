# Testing and CI/CD (Gradle)

Builds on [[Gradle]], [[Gradle Wrapper]], [[Consuming and Publishing Libraries]].

## Automated testing
- Test code lives in `src/test/java` (separate from `src/main/java`).
- Add a framework — modern default **JUnit 5**:
  ```groovy
  dependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2' }
  test { useJUnitPlatform() }
  ```
  (`testImplementation` = visible only to test code, never shipped — same family as `api`/`implementation`.)
- A test = class with `@Test` methods that **assert**:
  ```java
  @Test void crossProductIsPerpendicular() {
      assertEquals(new Vec3(0,0,1), Vec3.cross(new Vec3(1,0,0), new Vec3(0,1,0)));
  }
  ```
- `.\gradlew.bat test` runs them; report in `build/reports/tests/`. The `test` task is wired into `build` (via `check`), so **a failing test fails the build** — the hook CI relies on.
- `gradle init --type java-library` scaffolds JUnit + a sample test.

## CI/CD
- **CI** = on every push/PR, a server auto-builds + tests. **CD** = auto-publish/deploy on pass.
- Gradle fits because of the [[Gradle Wrapper]]: CI machine needs **only a JDK**; `./gradlew build` reproduces the local build exactly; exit code = pass/fail.
- Minimal GitHub Actions:
  ```yaml
  on: [push, pull_request]
  jobs:
    build:
      runs-on: ubuntu-latest
      steps:
        - uses: actions/checkout@v4
        - uses: actions/setup-java@v4
          with: { distribution: 'temurin', java-version: '21' }
        - run: ./gradlew build
  ```

## The graphics wrinkle (important for jvre)
Standard CI runners have **no GPU**. Tests split:

| Kind | GPU? | CI-friendly | jvre examples |
|---|---|---|---|
| Logic/unit | no | easy ✅ | math, scene graph, resource bookkeeping, parsing |
| Rendering/integration | yes | hard ⚠️ | "does it actually draw correctly?" |

Strategies for the rendering half:
- **Software Vulkan** — Mesa **lavapipe** or Google **SwiftShader**: a CPU Vulkan device, headless, no GPU needed. The key enabler for graphics CI.
- **Validation-as-a-test** — headless render with **validation layers** on; fail the build on any validation message. Huge bug class caught, runs on lavapipe, no image diff. Highest-value check for an explicit API.
- **Golden-image / snapshot** — render offscreen, pixel-diff vs a stored reference (with tolerance). Visual-regression catch. lavapipe in CI, or real RTX 4090 locally.
- **Self-hosted runner** — register the 4090 for hardware tests.

**Architecture lesson:** separating engine *logic* from raw *rendering* makes the logic testable everywhere — another reason jvre puts clean abstractions between "what to draw" and "Vulkan calls."

#concept #tooling #testing
