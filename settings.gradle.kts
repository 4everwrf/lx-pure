pluginManagement {
    val useChinaMirrors = System.getenv("CI") != "true" &&
        (System.getenv("USE_CHINA_MIRRORS") == "true" || java.util.Locale.getDefault().country.equals("CN", ignoreCase = true))
    repositories {
        if (useChinaMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useChinaMirrors = System.getenv("CI") != "true" &&
        (System.getenv("USE_CHINA_MIRRORS") == "true" || java.util.Locale.getDefault().country.equals("CN", ignoreCase = true))
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useChinaMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LXPure"
include(":app")
