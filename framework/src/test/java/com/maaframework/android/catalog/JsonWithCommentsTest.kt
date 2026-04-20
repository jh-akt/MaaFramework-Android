package com.maaframework.android.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonWithCommentsTest {
    @Test
    fun `stripLineComments removes double slash comments outside strings`() {
        val source = """
            {
              "task": "AndroidOpenGame", // keep this removed
              "label": "value://not-a-comment"
            }
        """.trimIndent()

        val stripped = JsonWithComments.stripLineComments(source)

        assertFalse(stripped.contains("keep this removed"))
        assertTrue(stripped.contains("value://not-a-comment"))
    }
}
