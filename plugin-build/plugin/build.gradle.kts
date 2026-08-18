plugins {
    `kotlin-dsl`
    alias(libs.plugins.pluginPublish)
}

dependencies {
    // kotlin-compiler-embeddable for PSI parsing (compileOnly — loaded at runtime via isolated Worker classloader)
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.gradle.plugin)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.compiler.embeddable)
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        create(property("ID").toString()) {
            id = property("ID").toString()
            implementationClass = property("IMPLEMENTATION_CLASS").toString()
            version = project.version.toString()
            description = property("DESCRIPTION").toString()
            displayName = property("DISPLAY_NAME").toString()
            tags.set(listOf("kotlin", "native", "jvm", "ffm", "interop"))
        }
    }
}

gradlePlugin {
    website.set(property("WEBSITE").toString())
    vcsUrl.set(property("VCS_URL").toString())
}

/*
publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "nna"
        }
    }
}
*/

tasks.register("setupPluginUploadFromEnvironment") {
    description = "Upload plugin to publish"

    doLast {
        val key = System.getenv("GRADLE_PUBLISH_KEY")
        val secret = System.getenv("GRADLE_PUBLISH_SECRET")

        if (key == null || secret == null) {
            throw GradleException("gradlePublishKey and/or gradlePublishSecret are not defined environment variables")
        }

        System.setProperty("gradle.publish.key", key)
        System.setProperty("gradle.publish.secret", secret)
    }
}
