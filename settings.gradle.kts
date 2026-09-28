pluginManagement {
    repositories {
        mavenCentral {
            content {
                includeGroup("com.vanniktech.maven.publish")
                includeGroup("com.vanniktech")
            }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "cbssh"

include(":sshlib")
include(":testapp")
include(":protocol")

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
