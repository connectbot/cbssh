pluginManagement {
    resolutionStrategy {
        eachPlugin {
            // Resolve Vanniktech directly from its Maven Central implementation artifact.
            if (requested.id.id == "com.vanniktech.maven.publish") {
                useModule("com.vanniktech:gradle-maven-publish-plugin:${requested.version}")
            }
        }
    }
    repositories {
        mavenCentral {
            content {
                includeGroup("com.vanniktech")
            }
        }
        gradlePluginPortal {
            content {
                excludeGroup("com.vanniktech")
            }
        }
    }
}

rootProject.name = "cbssh"

include(":sshlib")
include(":testapp")
include(":protocol")
include(":sshlib-webauthn")

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
