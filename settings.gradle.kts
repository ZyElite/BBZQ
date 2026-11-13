include(":app")
pluginManagement {
    repositories {
        // Gradle Plugin Portal mirror for networks that cannot access plugins.gradle.org.
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Maven Central mirror for application dependencies and their transitive dependencies.
        maven(url = "https://maven.aliyun.com/repository/central")
        mavenCentral()
        maven(url = "https://api.xposed.info")
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "BBZQ"
