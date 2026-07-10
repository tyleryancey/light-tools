package dev.tyler.lightledger.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnMapperTest {
    private val table = CsvTable(
        headers = listOf("Date", "Description", "Amount"),
        rows = listOf(
            listOf("01/15/2026", "Coffee Shop", "-4.50"),
            listOf("01/16/2026", "Paycheck", "1200.00"),
        ),
    )

    @Test fun mapsSingleAmountColumn() {
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1)
        val rows = ColumnMapper.map(table, mapping)
        assertEquals(LocalDate.of(2026, 1, 15), rows[0].date)
        assertEquals(-450L, rows[0].amountMinor)
        assertEquals("Coffee Shop", rows[0].payee)
    }

    @Test fun negatesAmountsWhenConfigured() {
        val mapping = CsvColumnMapping(
            dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1, negateAmounts = true,
        )
        val rows = ColumnMapper.map(table, mapping)
        assertEquals(450L, rows[0].amountMinor)
    }

    @Test fun mapsDebitColumnAsNegative() {
        val debitCreditTable = CsvTable(
            headers = listOf("Date", "Description", "Debit", "Credit"),
            rows = listOf(listOf("01/15/2026", "Coffee Shop", "4.50", "")),
        )
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", debitCol = 2, creditCol = 3, payeeCol = 1)
        val rows = ColumnMapper.map(debitCreditTable, mapping)
        assertEquals(-450L, rows[0].amountMinor)
    }

    @Test fun mapsCreditColumnAsPositive() {
        val debitCreditTable = CsvTable(
            headers = listOf("Date", "Description", "Debit", "Credit"),
            rows = listOf(listOf("01/16/2026", "Paycheck", "", "1200.00")),
        )
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", debitCol = 2, creditCol = 3, payeeCol = 1)
        val rows = ColumnMapper.map(debitCreditTable, mapping)
        assertEquals(120000L, rows[0].amountMinor)
    }

    @Test fun usesMemoColumnWhenPresent() {
        val memoTable = CsvTable(
            headers = listOf("Date", "Description", "Amount", "Memo"),
            rows = listOf(listOf("01/15/2026", "Coffee Shop", "-4.50", "extra hot")),
        )
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1, memoCol = 3)
        val rows = ColumnMapper.map(memoTable, mapping)
        assertEquals("extra hot", rows[0].memo)
    }

    @Test fun defaultsMemoToEmptyWhenNoMemoColumn() {
        val mapping = CsvColumnMapping(dateCol = 0, dateFormat = "MM/dd/yyyy", amountCol = 2, payeeCol = 1)
        val rows = ColumnMapper.map(table, mapping)
        assertEquals("", rows[0].memo)
    }
}
