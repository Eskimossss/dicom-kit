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
        maven {
            url = uri("https://www.dcm4che.org/maven2/")
        }
        maven {
            url = uri("https://raw.githubusercontent.com/nroduit/mvn-repo/master/")
        }
    }
}

rootProject.name = "DICOMTool"
include(":app")
