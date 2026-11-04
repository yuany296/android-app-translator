plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

/**
 * 真机测试安全闸门（2026-11-03）。
 *
 * `connected*AndroidTest` 结束后 Gradle/AGP 会自动卸载主 app，连同 files/、
 * shared_prefs/ 一起清空；该卸载走 ddmlib，permission.bash 里的 adb uninstall
 * deny 规则拦不住。这里在任务图执行前直接拒绝，除非显式放行。
 *
 * 安全替代：scripts\run_device_test_safe.bat
 * 确实要跑 connected 测试：先 scripts\backup_device_data.bat，再加
 * `-PALLOW_CONNECTED_ANDROID_TEST=true`。
 */
gradle.taskGraph.whenReady(
    object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
        override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
            val connectedAndroidTest = graph.allTasks.any { task ->
                task.name.startsWith("connected") && task.name.endsWith("AndroidTest")
            }
            val explicitlyAllowed =
                gradle.startParameter.projectProperties["ALLOW_CONNECTED_ANDROID_TEST"] == "true"
            if (connectedAndroidTest && !explicitlyAllowed) {
                throw GradleException(
                    "connected*AndroidTest will uninstall the main app and wipe its local data.\n" +
                        "Use scripts\\run_device_test_safe.bat instead.\n" +
                        "If you really need it, run scripts\\backup_device_data.bat first and pass " +
                        "-PALLOW_CONNECTED_ANDROID_TEST=true."
                )
            }
        }
    }
)
