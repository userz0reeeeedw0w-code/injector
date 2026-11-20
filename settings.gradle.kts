pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "NetScope"

include(
  ":app",
  ":core:shared",
  ":core:data",
  ":core:proxy",
  ":core:network",
  ":feature:traffic",
  ":feature:apiclient",
  ":feature:analytics",
  ":feature:settings",
  ":feature:injector",
)
