package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvParserTest {
    @Test fun parsesSimpleCsv() {
        val table = CsvParser.parse("date,payee,amount\n2026-01-01,Coffee,-4.50\n")
        assertEquals(listOf("date", "payee", "amount"), table.headers)
        assertEquals(listOf(listOf("2026-01-01", "Coffee", "-4.50")), table.rows)
    }

    @Test fun handlesQuotedFieldsWithEmbeddedDelimiterAndEscape() {
        val table = CsvParser.parse("payee,memo\n\"Coffee, Inc\",\"Says \"\"hi\"\"\"\n")
        assertEquals(listOf("Coffee, Inc", "Says \"hi\""), table.rows.first())
    }

    @Test fun toleratesCrlfLineEndings() {
        val table = CsvParser.parse("a,b\r\n1,2\r\n3,4\r\n")
        assertEquals(2, table.rows.size)
    }

    @Test fun stripsUtf8Bom() {
        val table = CsvParser.parse("﻿a,b\n1,2\n")
        assertEquals(listOf("a", "b"), table.headers)
    }

    @Test fun skipsFullyEmptyLines() {
        val table = CsvParser.parse("a,b\n1,2\n\n3,4\n")
        assertEquals(2, table.rows.size)
    }

    @Test fun padsRaggedShortRows() {
        val table = CsvParser.parse("a,b,c\n1,2\n")
        assertEquals(listOf("1", "2", ""), table.rows.first())
    }

    @Test fun truncatesRaggedLongRows() {
        val table = CsvParser.parse("a,b\n1,2,3\n")
        assertEquals(listOf("1", "2"), table.rows.first())
    }

    @Test fun detectsSemicolonDelimiter() {
        val table = CsvParser.parse("a;b;c\n1;2;3\n")
        assertEquals(listOf("a", "b", "c"), table.headers)
        assertEquals(listOf("1", "2", "3"), table.rows.first())
    }

    @Test fun detectsTabDelimiter() {
        val table = CsvParser.parse("a\tb\n1\t2\n")
        assertEquals(listOf("a", "b"), table.headers)
    }
}
