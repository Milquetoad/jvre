# Gradle Wrapper

A tiny launcher committed *into the project* that "wraps" the real [[Gradle]], guaranteeing everyone builds with the **same Gradle version** — without installing Gradle at all.

## Files
- `gradlew.bat` / `gradlew` — launcher scripts (Windows / Unix)
- `gradle/wrapper/gradle-wrapper.jar` — the bootstrapper
- `gradle/wrapper/gradle-wrapper.properties` — names the version:
  `distributionUrl=...gradle-8.10.2-bin.zip`

## How it works
Running `.\gradlew.bat run`:
1. Reads the properties → "this project wants Gradle 8.10.2".
2. Already downloaded in the local cache? If not, fetch it from the URL (we saw this `Downloading ...` line on first run).
3. Delegate the actual command (`run`) to that exact Gradle.

## Why
Reproducibility for the build tool **itself** — the same theme as [[Build Tools]], one level up. (winget didn't even have Gradle; the wrapper made that irrelevant.)

#concept #tooling
