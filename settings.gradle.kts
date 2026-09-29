pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Mozilla GeckoView（Firefox 内核内嵌进 App；站点隔离 + SharedArrayBuffer + WASM 多线程）
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "H5McpEngine"
include(":app")
