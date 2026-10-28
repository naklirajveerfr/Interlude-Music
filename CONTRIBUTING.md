# Contributing to Interlude Music

First off, thank you for considering contributing to **Interlude Music!** ❤️

Interlude is an open-source, unofficial YouTube Music client for Android, built with Material 3 Expressive and based on [Metrolist](https://github.com/MetrolistGroup/Metrolist).

Whether you're fixing bugs, implementing features, improving the UI, updating documentation, or simply suggesting ideas, your contributions are appreciated.

## Ways to Contribute

There are several ways you can help improve Interlude:

- **Bug Reports:** Help identify crashes, glitches, and unexpected behavior.
- **Feature Requests:** Suggest new features or improvements to existing ones.
- **Code Contributions:** Fix bugs, optimize performance, or implement new functionality.
- **UI/UX Improvements:** Help refine Interlude's appearance, animations, and overall user experience.
- **Documentation:** Improve the README, installation instructions, and other documentation.
- **Translations:** Help make Interlude accessible to more people.

You don't need to be an experienced developer to contribute!

## Reporting Bugs

Before reporting a bug, please check the [existing issues](https://github.com/naklirajveerfr/Interlude-Music/issues) to avoid duplicates.

When creating a bug report, try to include:

1. **Interlude Version:** The version you're using.
2. **Android Version:** Your device's Android version.
3. **Device Information:** Your device model, if relevant.
4. **Description:** A clear explanation of the issue.
5. **Steps to Reproduce:** How someone else can reproduce the problem.
6. **Expected Behavior:** What you expected to happen.
7. **Actual Behavior:** What happened instead.
8. **Screenshots or Logs:** Include these when possible, but remove personal information.

The more details you provide, the easier it is to investigate and fix the issue.

## Suggesting Features

Have an idea that could make Interlude better?

Feel free to [open an issue](https://github.com/naklirajveerfr/Interlude-Music/issues) and describe your suggestion.

A good feature request should explain:

- What feature you'd like to see.
- Why it would be useful.
- How you imagine it working.
- Any relevant examples, screenshots, or references.

Not every feature request will be implemented, but all constructive suggestions are welcome.

For larger changes, please open an issue to discuss your idea before starting development.

## Development Setup

### Prerequisites

To build Interlude, you'll need:

- Android Studio or a compatible Android development environment.
- A JDK compatible with the project's Gradle configuration.
- Android SDK.
- Git.

### Getting Started

**1. Fork the repository**

Fork [Interlude Music](https://github.com/naklirajveerfr/Interlude-Music) to your GitHub account.

**2. Clone your fork**

```bash
git clone https://github.com/YOUR_USERNAME/Interlude-Music.git
cd Interlude-Music
```

**3. Create a new branch**

```bash
git checkout -b feature/your-feature-name
```

**4. Make your changes**

Open the project in Android Studio and start developing.

**5. Build and test**

```bash
./gradlew :app:assembleFossDebug
```

Make sure the project builds successfully and test your changes on an Android device or emulator whenever possible.

## Pull Request Guidelines

When submitting a pull request, please:

1. **Keep changes focused:** Avoid combining unrelated features and fixes into a single PR.
2. **Follow existing code conventions:** Maintain consistency with the project's Kotlin, Jetpack Compose, and Material 3 Expressive patterns.
3. **Test your changes:** Ensure your code builds and functions as expected.
4. **Describe your work:** Clearly explain what your PR changes and why.
5. **Include screenshots:** For UI changes, provide before-and-after screenshots or screen recordings when possible.
6. **Reference related issues:** Link any issues your PR addresses.

Please avoid submitting unrelated formatting changes or unnecessary dependencies.

Pull requests may receive feedback or requests for changes before being merged.

## Design Guidelines

Interlude aims to provide a modern, expressive, and customizable listening experience.

When contributing UI changes:

- Follow **Material 3 Expressive** design principles.
- Maintain consistency with existing components and player styles.
- Support both light and dark themes where applicable.
- Respect user customization preferences.
- Keep animations smooth and avoid unnecessary performance overhead.
- Consider different screen sizes and orientations.
- Prioritize usability and accessibility alongside visual appearance.

New designs should feel like a natural part of Interlude.

## Code Quality

Please try to:

- Write readable, maintainable code.
- Avoid unnecessary complexity.
- Reuse existing components where practical.
- Handle errors gracefully.
- Avoid introducing performance regressions.
- Keep debugging code and unrelated changes out of pull requests.

Perfect code isn't expected, but thoughtful contributions are appreciated.

## Licensing and Attribution

Interlude is based on [Metrolist](https://github.com/MetrolistGroup/Metrolist) and is distributed under the **GNU General Public License v3.0 (GPL-3.0)**.

By submitting a contribution, you agree that your contribution can be distributed under the project's GPL-3.0 license.

Please respect third-party licenses and provide appropriate attribution when incorporating code, assets, or ideas from other projects.

Do not submit proprietary code or copyrighted assets without the necessary permissions.

## Code of Conduct

All contributors are expected to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

Please keep discussions respectful, constructive, and welcoming.

## A Final Note

Interlude is a passion project that's constantly evolving. Some features are experimental, some are still being refined, and there's always room for improvement.

Every contribution matters, whether it's a major feature, a tiny bug fix, or a helpful suggestion.

**Thank you for helping make Interlude Music better!** 🎵
