pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "invisible"
include(":oskit", ":os", ":programs:echo", ":programs:guard", ":programs:duel", ":programs:compass", ":programs:practice", ":programs:write", ":adapters")
