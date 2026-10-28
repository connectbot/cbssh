/*
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.vanniktech.maven.publish.DeploymentValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.metalava)
    alias(libs.plugins.kover)
    alias(libs.plugins.cyclonedx)
    `java-library`
}

apply(from = rootProject.file("gradle/publication-staging.gradle.kts"))

dependencies {
    api(project(":sshlib"))
    implementation(project(":protocol"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test { useJUnitPlatform() }

kover {
    currentProject {
        sources {
            excludeJava.set(true)
        }
    }
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

val gitHubUrl = "https://github.com/connectbot/cbssh"

dokka {
    moduleName.set("ConnectBot SSH WebAuthn Adapter")

    dokkaSourceSets.configureEach {
        includes.from("README.md")
        sourceLink {
            localDirectory.set(file("./"))
            remoteUrl.set(uri("$gitHubUrl/blob/main"))
            remoteLineSuffix.set("#L")
        }
    }

    pluginsConfiguration {
        html.footerMessage.set("Copyright Kenny Root")
    }
}

tasks.cyclonedxDirectBom {
    includeConfigs.set(listOf("runtimeClasspath"))
    includeLicenseText.set(true)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/${project.name}-bom.json"))
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifact(tasks.cyclonedxDirectBom.flatMap { it.jsonOutput }) {
                classifier = "cyclonedx"
                extension = "json"
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
    signAllPublications()

    pom {
        name.set("sshlib-webauthn")
        description.set("Optional WebAuthn adapter for ConnectBot SSH client library")
        inceptionYear.set("2019")
        url.set(gitHubUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("kruton")
                name.set("Kenny Root")
                url.set("https://github.com/kruton/")
            }
        }
        scm {
            connection.set("scm:git:$gitHubUrl.git")
            developerConnection.set("$gitHubUrl.git")
            url.set(gitHubUrl)
        }
    }
}
