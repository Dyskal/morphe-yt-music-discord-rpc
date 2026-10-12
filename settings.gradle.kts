rootProject.name = "morphe-yt-music-discord-rpc"

pluginManagement {
    System.getenv("MORPHE_BUILD_DEPS")?.let { includeBuild("$it/patches-gradle-plugin") }
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.4"
}

settings {
    extensions {
        defaultNamespace = "app.dyskal.ytmrpc"
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

System.getenv("MORPHE_BUILD_DEPS")?.let { dependencies ->
    includeBuild("$dependencies/morphe-patcher") {
        dependencySubstitution {
            substitute(module("app.morphe:morphe-patcher")).using(project(":"))
        }
    }
}
