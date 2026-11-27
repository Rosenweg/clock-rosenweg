pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven { setUrl("https://www.jitpack.io") }
        // Rosenweg: commons-rosenweg (org.fossify:commons-Fork, ch.rosenweg4303:*) aus GitHub Packages.
        // Anmeldedaten lokal aus ~/.gradle/gradle.properties (gpr.user/gpr.key) oder GITHUB_ACTOR +
        // GPR_TOKEN; in Actions kommt das Repo per Init-Skript (.github/actions/vorbereiten).
        // Ohne Anmeldedaten nur mavenLocal (Rueckfall, publishToMavenLocal).
        val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
        val gprKey = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GPR_TOKEN")).orNull
        if (gprUser != null && gprKey != null) {
            maven {
                name = "RosenwegCommons"
                url = uri("https://maven.pkg.github.com/Rosenweg/commons-rosenweg")
                credentials {
                    username = gprUser
                    password = gprKey
                }
                content {
                    includeGroup("ch.rosenweg4303")
                    includeGroup("org.fossify")
                }
            }
        }
        mavenLocal()
    }
}
include(":app")
