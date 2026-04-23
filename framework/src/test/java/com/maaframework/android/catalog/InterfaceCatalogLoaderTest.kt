package com.maaframework.android.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceCatalogLoaderTest {
    private val loader = InterfaceCatalogLoader()

    @Test
    fun `parseCatalog keeps adb compatible tasks and filters unsupported controllers`() {
        val catalog = loader.parseCatalog(
            interfaceText = """
                {
                  "controller": [
                    { "name": "Compat", "type": "ADB" }
                  ],
                  "task": [
                    {
                      "name": "CustomTask",
                      "label": "${'$'}task.CustomTask.label",
                      "entry": "CustomTaskEntry"
                    },
                    {
                      "name": "AdbTask",
                      "label": "${'$'}task.AdbTask.label",
                      "entry": "AdbTaskEntry",
                      "controller": ["ADB"]
                    },
                    {
                      "name": "AliasTask",
                      "label": "${'$'}task.AliasTask.label",
                      "entry": "AliasTaskEntry",
                      "controller": ["Compat"]
                    },
                    {
                      "name": "DesktopOnlyTask",
                      "label": "${'$'}task.DesktopOnlyTask.label",
                      "entry": "DesktopOnlyTaskEntry",
                      "controller": ["Win32-Front"]
                    }
                  ]
                }
            """.trimIndent(),
            localeText = """
                {
                  "task.CustomTask.label": "自定义任务",
                  "task.AdbTask.label": "ADB 任务",
                  "task.AliasTask.label": "兼容控制器任务",
                  "task.DesktopOnlyTask.label": "桌面任务"
                }
            """.trimIndent(),
            importResolver = { error("unexpected import") },
        )

        assertEquals(
            listOf("CustomTask", "AdbTask", "AliasTask"),
            catalog.tasks.map { it.id },
        )
        assertFalse(catalog.tasks.any { it.id == "DesktopOnlyTask" })
    }

    @Test
    fun `parseCatalog keeps supported task options for visible tasks`() {
        val catalog = loader.parseCatalog(
            interfaceText = """
                {
                  "controller": [
                    { "name": "Compat", "type": "ADB" }
                  ],
                  "task": [
                    {
                      "name": "CustomTask",
                      "label": "${'$'}task.CustomTask.label",
                      "entry": "CustomTaskEntry",
                      "option": ["SharedOption", "UnsupportedOption"]
                    }
                  ],
                  "option": {
                    "SharedOption": {
                      "type": "switch",
                      "label": "${'$'}option.SharedOption.label",
                      "controller": ["Compat"],
                      "default_case": "Yes",
                      "cases": [
                        { "name": "Yes" },
                        { "name": "No" }
                      ]
                    },
                    "UnsupportedOption": {
                      "type": "switch",
                      "label": "${'$'}option.UnsupportedOption.label",
                      "controller": ["Win32-Front"],
                      "default_case": "Yes",
                      "cases": [
                        { "name": "Yes" },
                        { "name": "No" }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            localeText = """
                {
                  "task.CustomTask.label": "自定义任务",
                  "option.SharedOption.label": "共享配置",
                  "option.UnsupportedOption.label": "桌面配置"
                }
            """.trimIndent(),
            importResolver = { error("unexpected import") },
        )

        val task = catalog.tasks.single()
        assertEquals("CustomTask", task.id)
        assertTrue(task.options.any { it.id == "SharedOption" })
        assertFalse(task.options.any { it.id == "UnsupportedOption" })
    }
}
