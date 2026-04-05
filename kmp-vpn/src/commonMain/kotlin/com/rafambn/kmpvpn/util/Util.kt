package com.rafambn.kmpvpn.util

object Util {
    fun toArray(bytes: List<Byte>): ByteArray {
        val b = ByteArray(bytes.size)
        for (i in bytes.indices) {
            b[i] = bytes[i]
        }
        return b
    }

    fun isBlank(str: String?): Boolean {
        return str.isNullOrBlank()
    }

    fun isNotBlank(str: String?): Boolean {
        return !isBlank(str)
    }

    fun getBasename(name: String): String {
        val idx = name.indexOf('.')
        return if (idx == -1) name else name.substring(0, idx)
    }

    fun checkEndsWithSlash(str: String): String {
        return if (str.endsWith("/")) str else "$str/"
    }

    fun titleUnderline(len: Int): String {
        return repeat(len, '=')
    }

    fun repeat(times: Int, ch: Char): String {
        val l = StringBuilder()
        for (i in 0 until times) {
            l.append(ch)
        }
        return l.toString()
    }

    fun parseQuotedString(command: String): MutableList<String> {
        val args = ArrayList<String>()
        var escaped = false
        var quoted = false
        val word = StringBuilder()
        for (i in 0 until command.length) {
            val c = command[i]
            if (c == '"' && !escaped) {
                quoted = !quoted
            } else if (c == '\\' && !escaped) {
                escaped = true
            } else if (c == ' ' && !escaped && !quoted) {
                if (word.isNotEmpty()) {
                    args.add(word.toString())
                    word.setLength(0)
                }
            } else {
                word.append(c)
                escaped = false
            }
        }
        if (word.isNotEmpty()) {
            args.add(word.toString())
        }
        return args
    }
}
