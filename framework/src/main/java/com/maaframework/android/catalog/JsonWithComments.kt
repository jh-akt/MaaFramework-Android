package com.maaframework.android.catalog

object JsonWithComments {
    fun stripLineComments(input: String): String {
        val out = StringBuilder(input.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < input.length) {
            val ch = input[index]

            if (escaped) {
                out.append(ch)
                escaped = false
                index++
                continue
            }

            if (ch == '\\' && inString) {
                out.append(ch)
                escaped = true
                index++
                continue
            }

            if (ch == '"') {
                inString = !inString
                out.append(ch)
                index++
                continue
            }

            if (!inString && ch == '/' && index + 1 < input.length && input[index + 1] == '/') {
                while (index < input.length && input[index] != '\n') {
                    index++
                }
                continue
            }

            out.append(ch)
            index++
        }
        return out.toString()
    }
}
