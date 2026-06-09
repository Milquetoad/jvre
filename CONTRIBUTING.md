# Contributing to jvre

Thanks for your interest! jvre is primarily a learning project, but contributions, questions, and suggestions are welcome.

## Ground rules

- **Be kind and constructive.** This is a place to learn.
- **Keep changes focused.** One concern per pull request makes review easier.
- **Match the surrounding style.** The codebase favors heavily-commented, explained code — comments say *why*, not just *what* — because understanding is the point.

## Development setup

You need **JDK 21** and (recommended) the **Vulkan SDK** for validation layers. Then:

```bash
./gradlew run        # build and run (use gradlew.bat on Windows)
./gradlew build      # compile + run tests
```

No global Gradle install is required — the wrapper handles it.

## Contributor License Agreement (CLA)

jvre is released under the **AGPL-3.0**, but the project intends to keep the door open to **dual licensing** (offering commercial licenses to those who cannot accept the AGPL). For that to remain possible, the project must hold clear rights to all contributed code.

By submitting a contribution (e.g. a pull request), you agree that:

1. You are the original author of the contribution, or otherwise have the right to submit it.
2. You grant the project maintainer (Peder Godal) a perpetual, irrevocable, worldwide, royalty-free license to use, reproduce, modify, and **relicense** your contribution as part of jvre — including under licenses other than the AGPL-3.0.
3. Your contribution is and will remain available under the AGPL-3.0 as part of the project.

In plain terms: you keep authorship credit, your code stays open under the AGPL, and the maintainer can also offer the combined work under a commercial license. If you are not comfortable with this, please open an issue to discuss before contributing.

## Reporting issues

Open a GitHub issue with:

- What you expected vs. what happened.
- Steps to reproduce.
- Your OS, GPU + driver version, and JDK version.
- Any Vulkan validation-layer output (run with validation enabled).

## License

By contributing you agree your contributions are licensed under the [AGPL-3.0](LICENSE), subject to the CLA terms above.
