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

    @Test
    fun `parseCatalog infers option type when Maa option omits type`() {
        val catalog = loader.parseCatalog(
            interfaceText = """
                {
                  "task": [
                    {
                      "name": "Daily",
                      "entry": "Daily",
                      "option": ["BooleanOption", "ChoiceOption"]
                    }
                  ],
                  "option": {
                    "BooleanOption": {
                      "cases": [
                        { "name": "YES" },
                        { "name": "NO" }
                      ]
                    },
                    "ChoiceOption": {
                      "cases": [
                        { "name": "A" },
                        { "name": "B" },
                        { "name": "C" }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            localeText = null,
            importResolver = { error("unexpected import") },
        )

        val options = catalog.tasks.single().options
        val booleanOption = options.single { it.id == "BooleanOption" }
        val choiceOption = options.single { it.id == "ChoiceOption" }
        assertEquals("开启", booleanOption.cases.first { it.name == "YES" }.label)
        assertEquals("关闭", booleanOption.cases.first { it.name == "NO" }.label)
        assertEquals(com.maaframework.android.model.TaskOptionType.Switch, booleanOption.type)
        assertEquals(com.maaframework.android.model.TaskOptionType.Select, choiceOption.type)
    }

    @Test
    fun `parseCatalog resolves Maa style option locale keys`() {
        val catalog = loader.parseCatalog(
            interfaceText = """
                {
                  "task": [
                    {
                      "name": "SellProduct",
                      "label": "${'$'}task.SellProduct.label",
                      "entry": "SellProduct",
                      "option": ["PriorityItem", "CountOption"]
                    }
                  ],
                  "option": {
                    "PriorityItem": {
                      "type": "select",
                      "label": "${'$'}task.SellProduct.PriorityItem1",
                      "default_case": "无",
                      "cases": [
                        { "name": "无" },
                        {
                          "name": "精选荞愈胶囊",
                          "label": "${'$'}item.BuckCapsuleA"
                        }
                      ]
                    },
                    "CountOption": {
                      "type": "input",
                      "label": "${'$'}option.CountOption.label",
                      "inputs": [
                        {
                          "name": "MaxCount",
                          "default": "1",
                          "verify": "[0-9]+",
                          "pattern_msg": "${'$'}option.CountOption.MaxCount.error"
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            localeText = """
                {
                  "task.SellProduct.label": "售卖产品",
                  "task.SellProduct.PriorityItem1": "优先物品 1",
                  "item.BuckCapsuleA": "精选荞愈胶囊",
                  "option.CountOption.label": "数量设置",
                  "option.CountOption.inputs.MaxCount.label": "最大数量",
                  "option.CountOption.inputs.MaxCount.description": "达到数量后停止",
                  "option.CountOption.MaxCount.error": "请输入数字"
                }
            """.trimIndent(),
            importResolver = { error("unexpected import") },
        )

        val task = catalog.tasks.single()
        assertEquals("售卖产品", task.label)

        val priority = task.options.single { it.id == "PriorityItem" }
        assertEquals("优先物品 1", priority.label)
        assertEquals(listOf("无", "精选荞愈胶囊"), priority.cases.map { it.label })

        val input = task.options.single { it.id == "CountOption" }.inputs.single()
        assertEquals("最大数量", input.label)
        assertEquals("达到数量后停止", input.description)
        assertEquals("请输入数字", input.patternMessage)
    }
}
