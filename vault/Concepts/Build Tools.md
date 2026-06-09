# Build Tools

A **build tool** automates turning a *description* of a project into the actual steps to fetch dependencies, compile, test, package, and run it — **reproducibly**.

## The problem they solve
By hand you'd: download every dependency jar (and *their* dependencies), assemble a giant `-classpath`, run `javac`, copy resources, package, run — every time, on every machine. Error-prone and non-reproducible ("works on my machine").

## What they do
- **Dependency management** — "I need [[LWJGL]] 3.3.4" → downloads it *plus its transitive deps* from a repository (Maven Central). This is the most valuable part.
- **Compilation** — runs `javac` with the correct classpath.
- **Running / testing / packaging** — the full lifecycle.
- **Reproducibility** — the build description is a file committed in the project.

## The two big ones (Java)
- **Maven** — XML (`pom.xml`); convention-heavy, rigid, declarative-only. Predictable.
- **[[Gradle]]** — script (`build.gradle`); flexible, programmable, faster. **We use this** (we'll add custom logic later, e.g. compile shaders to SPIR-V before building).

A project uses one *or* the other, not both.

#concept #tooling
