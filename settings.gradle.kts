pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/dangvansam/viet-asr")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    } }
rootProject.name = "CorosDjiStudio"
include(":app")
