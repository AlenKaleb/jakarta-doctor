# Jakarta Doctor

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ-2024.3-blue.svg)](https://www.jetbrains.com/idea/)

**Jakarta Doctor** is an IntelliJ IDEA plugin designed to assist developers in migrating Spring Boot 2.x projects to Spring Boot 3.x by automatically detecting and converting `javax.*` imports to their `jakarta.*` equivalents.

## 🌟 Features

- **🔍 Smart Import Detection**: Automatically identifies `javax.*` imports that need to be migrated to `jakarta.*`
- **⚡ Quick Fixes**: One-click quick fixes for individual imports directly from the IDE
- **🔄 Batch Migration**: Migrate all imports in selected scope (file, directory, or entire project) at once
- **📊 Migration Report**: Generate comprehensive Markdown reports showing:
  - Total count of `javax` imports found
  - Number of affected files
  - Breakdown by package
- **☕ Java & Kotlin Support**: Works seamlessly with both Java and Kotlin source files
- **✅ IntelliJ Integration**: Full integration with IntelliJ's inspection system and code analysis

### Supported Packages

The plugin currently supports migration for the following packages:

- `javax.persistence` → `jakarta.persistence`
- `javax.validation` → `jakarta.validation`
- `javax.servlet` → `jakarta.servlet`
- `javax.annotation` → `jakarta.annotation`
- `javax.ws.rs` → `jakarta.ws.rs`

## 🚀 Installation

### From JetBrains Marketplace (Coming Soon)

1. Open IntelliJ IDEA
2. Go to **File** → **Settings** → **Plugins**
3. Search for "Jakarta Doctor"
4. Click **Install**
5. Restart the IDE

### Manual Installation

1. Download the latest plugin ZIP from the [Releases](https://github.com/AlenKaleb/jakarta-doctor/releases) page
2. Open IntelliJ IDEA
3. Go to **File** → **Settings** → **Plugins**
4. Click the gear icon (⚙️) and select **Install Plugin from Disk...**
5. Select the downloaded ZIP file
6. Restart the IDE

## 📖 Usage

### Using Inspections

1. Open any Java or Kotlin file in your project
2. Jakarta Doctor will automatically highlight `javax.*` imports that can be migrated
3. Place your cursor on the highlighted import
4. Press **Alt+Enter** (or **Option+Return** on Mac) to show the quick fix
5. Select **"Migrate javax→jakarta"** to apply the fix

### Using the Tools Menu

#### Generate Migration Report

1. Go to **Tools** → **Jakarta Doctor** → **Generate Jakarta Migration Report**
2. A Markdown report will be generated and opened in the editor
3. The report shows:
   - Total number of `javax` imports
   - Number of affected files
   - Detailed breakdown by package

#### Batch Migration

1. Select the scope you want to migrate (file, directory, or project root)
2. Go to **Tools** → **Jakarta Doctor** → **Migrate Jakarta Imports (Batch)**
3. All eligible imports in the selected scope will be migrated automatically
4. Review the changes and commit when satisfied

## 🛠️ Development

### Prerequisites

- **JDK 21** or higher
- **IntelliJ IDEA 2024.3** or higher
- **Gradle 8.x** (included via wrapper)

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/AlenKaleb/jakarta-doctor.git
   cd jakarta-doctor
   ```

2. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```

3. The plugin ZIP will be available in `build/distributions/`

### Running in Development Mode

To test the plugin in a sandboxed IntelliJ instance:

```bash
./gradlew runIde
```

### Running Tests

```bash
./gradlew test
```

### Project Structure

```
jakarta-doctor/
├── src/main/kotlin/
│   └── com/alenkaleb/jakartadoctor/
│       ├── actions/           # Batch migration actions
│       ├── inspection/        # Code inspection and quick fixes
│       ├── report/            # Report generation
│       └── licensing/         # License management
├── src/main/resources/
│   ├── META-INF/
│   │   └── plugin.xml        # Plugin configuration
│   └── icons/                # Plugin icons
├── build.gradle.kts          # Build configuration
└── README.md
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Adding Support for More Packages

To add support for additional `javax.*` to `jakarta.*` migrations:

1. Edit `JakartaImportInspection.kt`
2. Add the mapping to the `javaxToJakarta` map in the companion object
3. Test your changes
4. Submit a PR

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 👨‍💻 Author

**Alen Kaleb**

- Email: kalebanjos@gmail.com
- GitHub: [@AlenKaleb](https://github.com/AlenKaleb)

## 🙏 Acknowledgments

- Built with the [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- Inspired by the need for smoother Spring Boot 2.x to 3.x migrations
- Thanks to the JetBrains team for their excellent plugin development tools

## 📋 Changelog

### Version 2026.2.0 (Check if classpath Jakarta exists in project)

- ✨ Initial release
- ✅ Code inspection for `javax.*` imports
- ⚡ Quick fix for individual imports
- 🔄 Batch migration action
- 📊 Migration report generation
- 💼 Support for Java and Kotlin files
- 🎯 Compatibility with IntelliJ IDEA 2024.3

## 🐛 Issues & Support

If you encounter any issues or have questions:

1. Check the [Issues](https://github.com/AlenKaleb/jakarta-doctor/issues) page
2. Create a new issue with:
   - IntelliJ IDEA version
   - Plugin version
   - Steps to reproduce
   - Expected vs actual behavior

---

Made with ❤️ for the Java/Kotlin community
