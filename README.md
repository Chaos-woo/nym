# Nym — AI-powered Naming & Comments for IntelliJ IDEA

Nym is an IntelliJ Platform plugin that helps you name things better and generate documentation comments with the help of LLMs. It integrates seamlessly into the editor with lightweight actions, a settings page, and a simple Tool Window for usage statistics.

This README explains what the plugin does, how to install it, how to configure AI providers, and how to use each feature safely and effectively.

- IntelliJ Platform: since-build 251 (IntelliJ IDEA 2025.1 or later)
- JDK: 21
- Kotlin: 2.1 (K2)


## Table of contents
- Overview
- Features
- Requirements
- Installation
  - Run from sources
  - Install from disk (ZIP)
- Configuration
  - AI Provider and API Key
  - Performance
  - Preferences
- Usage
  - Generate Naming
  - Generate Comment
  - Custom Generate
  - Analyze Unused Code
  - Nym Tool Window
- Privacy & Security
- Troubleshooting
- Development
- Contributing
- License


## Overview
Nym augments your coding workflow by using an LLM provider (OpenAI or any OpenAI-compatible endpoint) to:
- Suggest better names for classes, methods, and variables.
- Generate documentation comments for the selected element.
- Run custom prompt-based generations with code context.
- Provide basic usage statistics in a dedicated Tool Window.

All heavy work is executed in background tasks to keep the IDE responsive, and sensitive data like API keys are stored in IntelliJ Password Safe.


## Features
- Editor actions (right-click menu and Code menu):
  - Nym: Generate Naming
  - Nym: Generate Comment
  - Nym: Custom Generate
- Tools menu action:
  - Nym: Analyze Unused Code (scans for unused files/classes/methods and reports counts)
- Settings page: configure provider, endpoint, model, temperature, API key, timeouts and concurrency.
- Tool Window: shows usage statistics and token counters; manual Refresh.
- i18n: English and Simplified Chinese resources.


## Requirements
- IntelliJ IDEA 2025.1+ (since-build 251)
- JDK 21
- Internet access to your chosen AI provider (unless using a locally hosted, OpenAI-compatible endpoint)


## Installation

### Run from sources (recommended for development)
1. Clone the repository.
2. Ensure JDK 21 is available and selected for Gradle.
3. From a terminal:
   - Windows: `gradlew.bat runIde`
   - macOS/Linux: `./gradlew runIde`
4. A sandbox IDE will start with Nym installed.

### Install from disk (ZIP)
1. Build the plugin ZIP:
   - Windows: `gradlew.bat buildPlugin`
   - macOS/Linux: `./gradlew buildPlugin`
2. In IntelliJ IDEA: Settings/Preferences > Plugins > gear icon > Install Plugin from Disk…
3. Choose the ZIP from `build/distributions/`.


## Configuration
Open Settings/Preferences and navigate to Tools > Nym (displayed as “Nym”). Configure the following:

### AI Provider and API Key
- AI Provider: choose “OpenAI” or “Custom” (OpenAI-compatible APIs).
- API Endpoint:
  - OpenAI: keep default `https://api.openai.com/v1` or customize if needed.
  - Custom: provide your compatible base URL (for example, a self-hosted gateway).
- API Key: stored securely via IntelliJ Password Safe. You can Show/Hide/Clear the value.
- Model name: e.g., `gpt-4o-mini` (default). For custom providers, enter the model your endpoint supports.
- Temperature: 0.0–1.0 (exposed as a 0–100 slider) to balance determinism and creativity.
- Test Connection: performs a quick round-trip to verify provider/endpoint/key/model.

### Performance
- Request timeout (ms): per-request limit (default 60,000 ms).
- Max concurrent requests: global cap to protect IDE responsiveness (default 6).
- Cache max entries: upper bound for in-memory suggestion cache.
- Memory threshold (MB): a soft limit used for future adaptive throttling.

### Preferences
- Generation language: AUTO/EN/ZH for AI-generated text.
- UI language: AUTO/EN/ZH for plugin UI strings (AUTO follows IDE locale, else falls back to EN).
- Naming style: CAMEL_CASE/SNAKE_CASE (hint for generation and checks).
- Comment format: LINE/JAVADOC/JSDOC.


## Usage

### 1) Generate Naming
- Place the caret on a class, method/function, or variable/property in a Java or Kotlin file.
- Use one of:
  - Right-click the editor > Nym: Generate Naming
  - Code menu > Nym: Generate Naming
- Nym analyzes surrounding code context and asks your AI provider for suggestions.
- Suggestions are reported in a notification. Pick your preferred name and apply the rename manually (refactor shortcut or Rename).

### 2) Generate Comment
- Place the caret on a class or method/function without a comment.
- Run:
  - Right-click the editor > Nym: Generate Comment
  - Code menu > Nym: Generate Comment
- Nym builds a context snapshot and requests a documentation comment (e.g., Javadoc/KDoc based on Preferences). The generated comment is inserted at the target element.

### 3) Custom Generate
- Select some text to use as your custom prompt (e.g., “explain this algorithm”).
- Run: Right-click the editor > Nym: Custom Generate
- Nym mixes your selection with local code context and returns an AI result in a notification for quick copy.

### 4) Analyze Unused Code
- Tools menu > Nym: Analyze Unused Code.
- Scans the project and reports counts of unused files/classes/methods. This is a lightweight summary—no refactoring is performed.

### 5) Nym Tool Window
- View > Tool Windows > Nym (right side).
- Shows usage statistics: today/total counts, success rate, average latency, and token usage (today/week/month/total). Click Refresh to update.


## Privacy & Security
- API Key is stored via IntelliJ Password Safe and is not written in plain text to configuration files.
- Nym processes PSI/AST locally to build compact prompts. When you trigger an action, the relevant code context may be sent to your configured provider. Do not enable the plugin on projects where sending code to third parties is disallowed.
- Avoid using the plugin on the Event Dispatch Thread; all network/IO work is done in background tasks and can be canceled.


## Troubleshooting
- Test Connection fails:
  - Verify provider (OpenAI vs Custom), endpoint URL, API key, and model name.
  - Check network/proxy/corporate firewall. Try curl/Postman to confirm reachability.
  - Watch for provider rate limits or invalid credentials.
- Actions disabled or do nothing:
  - Ensure indexing is complete (Dumb Mode avoids index-dependent work). Try again when the IDE is “smart”.
  - Place the caret on a supported element (class/method/variable) for naming; on class/method for comments; select text for Custom Generate.
- Timeouts or slow responses:
  - Increase request timeout, reduce concurrency, or try a lighter model.
- Logs & notifications:
  - See IDE log and Nym notifications; sensitive data is not logged.


## Development
- Tooling: Gradle with IntelliJ Platform Gradle Plugin.
- Run sandbox: `gradlew.bat runIde` (Windows) or `./gradlew runIde` (macOS/Linux).
- Build ZIP: `gradlew.bat buildPlugin` or `./gradlew buildPlugin`.
- Source layout (Kotlin):
  - core, ai, domain, data, presentation, integrations, platform
  - resources: META-INF/plugin.xml, messages, icons
- Coding guidelines: Kotlin 2.1, no wildcard imports, null-safety first, internal visibility by default, Chinese comments for “why” in complex logic, and tests where applicable.


## Contributing
Contributions are welcome. Please follow Conventional Commits and ensure the project builds, static checks pass, and tests are green. Record any new dependencies in `docs/3rd_packages_recordings.txt` (name:version:reason) and review licenses.


## License
No explicit license is provided in this repository. All rights reserved by the authors unless stated otherwise. For clarification, please open an issue or contact the maintainers.
