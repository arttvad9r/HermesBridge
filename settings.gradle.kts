pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    buildscript {
        repositories {
            // R8 publishes stable/dev prebuilts through its official release Maven repository.
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content {
                    includeModule("com.android.tools", "r8")
                }
            }
            google()
            mavenCentral()
        }
        dependencies {
            // Kotlin 2.4 metadata requires R8 9.1.29+; override AGP 8.9.1's bundled R8.
            classpath("com.android.tools:r8:9.1.29")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HermesBridge"
include(":app", ":protocol", ":relay")
