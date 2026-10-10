import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("java-library")
    id("checkstyle")
    id("signing")
    id("maven-publish")
}

group = "net.elytrium"
version = "1.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.fastutil.core)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.jackson.core)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    checkstyle(libs.checkstyle)
}

tasks.test {
    useJUnitPlatform()
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = file("$rootDir/.config/checkstyle/checkstyle.xml")
    configProperties = mapOf("configDirectory" to "$rootDir/.config/checkstyle")
    maxErrors = 0
    maxWarnings = 0
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        source = "21"
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
        addStringOption("Xdoclint:none", "-quiet")
    }
}

publishing {
    repositories {
        maven {
            name = "elytrium-repo"
            url = uri("https://maven.elytrium.net/repo/")
            credentials {
                username = System.getenv("ELYTRIUM_MAVEN_USERNAME")
                password = System.getenv("ELYTRIUM_MAVEN_PASSWORD")
            }
        }
        maven {
            name = "ossrh-repo"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("ELYTRIUM_OSSRH_USERNAME")
                password = System.getenv("ELYTRIUM_OSSRH_PASSWORD")
            }
        }
    }

    publications.create<MavenPublication>("publication") {
        pom {
            name.set("Elytrium Java Serializer")
            description.set("Java Library to (de-)serialize data in various formats (e.g. JSON, YAML)")
            url.set("https://github.com/Elytrium/java-serializer")
            licenses {
                license {
                    name.set("GNU Lesser General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/lgpl-3.0.en.html")
                }
            }
            developers {
                developer {
                    id.set("hevav")
                    name.set("Petr Ilin")
                    email.set("p.ilin@elytrium.net")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/Elytrium/java-serializer.git")
                developerConnection.set("scm:git:ssh://github.com/Elytrium/java-serializer.git")
                url.set("https://github.com/Elytrium/java-serializer")
            }
        }
        from(components["java"])
    }
}

signing {
    if (rootProject.hasProperty("signing.keyId")) {
        sign(publishing.publications["publication"])
    }
}

// Transfer a completed Maven upload to the Portal for manual review and release.
tasks.register("stageCentralPublication") {
    group = "publishing"
    description = "Upload the publication and transfer it to Central Portal without releasing it."
    dependsOn("publishPublicationPublicationToOssrh-repoRepository")
    doLast {
        val username = System.getenv("ELYTRIUM_OSSRH_USERNAME")
        val password = System.getenv("ELYTRIUM_OSSRH_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            throw GradleException("Central Portal token credentials are required in ELYTRIUM_OSSRH_USERNAME/PASSWORD")
        }
        val namespace = providers.gradleProperty("centralNamespace").getOrElse(project.group.toString())
        val encodedNamespace = URLEncoder.encode(namespace, StandardCharsets.UTF_8)
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/$encodedNamespace?publishing_type=user_managed"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw GradleException("Central Portal transfer failed with HTTP ${response.statusCode()}")
        }
        logger.lifecycle("Publication transferred to https://central.sonatype.com/publishing for manual review and release.")
    }
}
