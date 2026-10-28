pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "invisible"
include(":oskit", ":os", ":programs:echo", ":programs:guard", ":programs:duel", ":programs:compass", ":programs:now", ":programs:memory", ":programs:quiz", ":programs:reaction", ":programs:clicktest", ":programs:reader", ":programs:claude", ":programs:whereami", ":programs:practice", ":programs:write", ":programs:habits", ":programs:counter", ":programs:breathe", ":adapters")
