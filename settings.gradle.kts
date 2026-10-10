pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext from GitHub Packages (public, but GitHub always requires a token)
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("USERNAME") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("TOKEN") ?: "")
            }
        }
    }
}

rootProject.name = "karoo-maps-bridge"
include("karoo-extension", "mobile-app")
