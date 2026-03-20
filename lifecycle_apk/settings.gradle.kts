pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack required for:
        //   com.github.InstantWebP2P:tweetnacl-java (ed25519 signing)
        //   com.github.PhilJay:MPAndroidChart       (PnL chart view)
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "LifecycleBot"
include(":app")
