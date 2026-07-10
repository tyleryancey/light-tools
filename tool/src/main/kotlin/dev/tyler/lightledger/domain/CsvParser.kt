package dev.tyler.lightledger.domain

data class CsvTable(val headers: List<String>, val rows: List<List<String>>)

object CsvParser {
    fun parse(rawText: String): CsvTable {
        val text = stripBom(rawText)
        val delimiter = detectDelimiter(text)
        val records = splitLines(text)
            .filter { it.isNotEmpty() }
            .map { parseLine(it, delimiter) }
        require(records.isNotEmpty()) { "CSV has no rows" }

        val headers = records.first()
        val rows = records.drop(1).map { row ->
            when {
                row.size == headers.size -> row
                row.size < headers.size -> row + List(headers.size - row.size) { "" }
                else -> row.take(headers.size)
            }
        }
        return CsvTable(headers, rows)
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text

    private fun splitLines(text: String): List<String> =
        text.split("\r\n", "\n").map { it.removeSuffix("\r") }

    private fun detectDelimiter(text: String): Char {
        val headerLine = text.split("\r\n", "\n").firstOrNull { it.isNotBlank() } ?: return ','
        val counts = listOf(',', ';', '\t').associateWith { d -> headerLine.count { it == d } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun parseLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
