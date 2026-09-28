pluginManagement {
    repositories {
        mavenCentral {
            content {
                includeGroup("com.vanniktech.maven.publish")
                includeGroup("com.vanniktech")
            }
        }
        gradlePluginPortal {
            content {
                // Resolve the publishing plugin and its implementation only from Maven Central.
                excludeGroup("com.vanniktech.maven.publish")
                excludeGroup("com.vanniktech")
            }
        }
    }
}

rootProject.name = "cbssh"

include(":sshlib")
include(":testapp")
include(":protocol")

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
