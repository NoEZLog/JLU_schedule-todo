pluginManagement {
	repositories {
		mavenLocal() // 本地 ucrop-2.2.11（jitpack 在当前网络不可达，AAR 已从源码构建并安装到 mavenLocal）
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenLocal() // 本地 ucrop-2.2.11
		google()
		mavenCentral()
		maven("https://jitpack.io")
	}
}

rootProject.name = "JLU_schedule"
include(":app")
