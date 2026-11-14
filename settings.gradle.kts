pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // přibalený prohlížeč pro přihlášení (GeckoView)
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "TestPreklad"
include(":app")
