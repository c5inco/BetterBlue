// Root build file. Plugins are declared per-module via the version catalog so that
// nothing Android-specific is resolved when :app is excluded (see settings.gradle.kts).
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
