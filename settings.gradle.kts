pluginManagement {
  repositories {
    // AGP itself can pull non-Android runtime artifacts such as JAXB.
    // Keep Google's Maven mirror available for those transitive plugin dependencies.
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "PlcBridge"

include(":app", ":usb_probe", ":usb_ecm")
