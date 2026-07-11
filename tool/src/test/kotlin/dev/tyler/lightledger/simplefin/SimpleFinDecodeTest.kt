package dev.tyler.lightledger.simplefin

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fixtures are hand-authored to match CLAUDE-light-ledger.md §6.1.4's Account Set
// sketch. Inlined as string literals (not loaded from src/test/resources via a
// classloader) because the light-sdk plugin's source scanner bans reflection-shaped
// tokens across all source sets, including tests.
private val FIXTURE_SINGLE_ACCOUNT = """
{
  "errors": [],
  "accounts": [
    {
      "id": "ACT-1111",
      "name": "Checking",
      "currency": "USD",
      "balance": "1234.56",
      "available-balance": "1200.00",
      "balance-date": 1751846400,
      "org": {"name": "Demo Bank", "domain": "demo.example"},
      "transactions": [
        {
          "id": "TRN-1",
          "posted": 1751846400,
          "amount": "-4.50",
          "description": "COFFEE SHOP",
          "pending": false,
          "payee": "Coffee Shop Co",
          "memo": "latte"
        },
        {
          "id": "TRN-2",
          "posted": 1751932800,
          "amount": "12.34",
          "description": "DIRECT DEPOSIT",
          "pending": true
        }
      ]
    }
  ]
}
""".trimIndent()

private val FIXTURE_WITH_ERRORS = """
{
  "errors": ["Please reauthenticate at bridge.simplefin.org"],
  "accounts": []
}
""".trimIndent()

private val FIXTURE_EMPTY_ACCOUNTS = """
{
  "errors": [],
  "accounts": []
}
""".trimIndent()

private val FIXTURE_MULTI_ACCOUNT = """
{
  "errors": [],
  "accounts": [
    {
      "id": "ACT-AAA",
      "name": "Checking",
      "currency": "USD",
      "transactions": [
        {"id": "TRN-A1", "posted": 1751846400, "amount": "-10.00", "description": "GROCERY"},
        {"id": "TRN-A2", "posted": 1751932800, "amount": "20.00", "description": "REFUND"}
      ]
    },
    {
      "id": "ACT-BBB",
      "name": "Savings",
      "currency": "USD",
      "transactions": [
        {"id": "TRN-B1", "posted": 1752019200, "amount": "500.00", "description": "TRANSFER IN"}
      ]
    }
  ]
}
""".trimIndent()

class SimpleFinDecodeTest {

    @Test
    fun decodesUnknownKeysWithoutFailing() {
        val accountSet = SimpleFinDecoder.decode(FIXTURE_SINGLE_ACCOUNT)
        assertEquals(1, accountSet.accounts.size)
        val account = accountSet.accounts.single()
        assertEquals("ACT-1111", account.id)
        assertEquals("Checking", account.name)
        assertEquals("USD", account.currency)
        assertEquals(2, account.transactions.size)
    }

    @Test
    fun mapsNegativeStringAmountToMinorUnits() {
        val account = SimpleFinDecoder.decode(FIXTURE_SINGLE_ACCOUNT).accounts.single()
        val mapped = SimpleFinMapper.toMappedTransactions(account)
        val txn = mapped.first { it.externalId == "TRN-1" }
        assertEquals(-450L, txn.amountMinor)
    }

    @Test
    fun mapsPositiveStringAmountToMinorUnits() {
        val account = SimpleFinDecoder.decode(FIXTURE_SINGLE_ACCOUNT).accounts.single()
        val mapped = SimpleFinMapper.toMappedTransactions(account)
        val txn = mapped.first { it.externalId == "TRN-2" }
        assertEquals(1234L, txn.amountMinor)
    }

    @Test
    fun mapsUnixPostedToDeviceLocalEpochDay() {
        val account = SimpleFinDecoder.decode(FIXTURE_SINGLE_ACCOUNT).accounts.single()
        val mapped = SimpleFinMapper.toMappedTransactions(account)
        val txn1 = mapped.first { it.externalId == "TRN-1" }
        val txn2 = mapped.first { it.externalId == "TRN-2" }

        // Zone-independent check: TRN-1 and TRN-2 are exactly 86400s (1 day) apart,
        // so their mapped epochDays must differ by exactly 1 regardless of the host's
        // default time zone.
        assertEquals(1L, txn2.postedEpochDay - txn1.postedEpochDay)

        // Also pin the transformation itself, re-deriving the expected value with the
        // same Instant -> systemDefault -> LocalDate -> epochDay chain the mapper uses,
        // to catch a wrong field or wrong zone being wired in.
        val expectedEpochDay = Instant.ofEpochSecond(1751846400L)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        assertEquals(expectedEpochDay, txn1.postedEpochDay)
    }

    @Test
    fun missingPayeeFallsBackToDescriptionAndMemoDefaultsEmpty() {
        val account = SimpleFinDecoder.decode(FIXTURE_SINGLE_ACCOUNT).accounts.single()
        val mapped = SimpleFinMapper.toMappedTransactions(account)
        val txn = mapped.first { it.externalId == "TRN-2" }
        assertEquals("DIRECT DEPOSIT", txn.payee)
        assertEquals("", txn.memo)
        assertTrue(txn.pending)
    }

    @Test
    fun presentPayeeAndMemoAreUsedDirectly() {
        val account = SimpleFinDecoder.decode(FIXTURE_SINGLE_ACCOUNT).accounts.single()
        val mapped = SimpleFinMapper.toMappedTransactions(account)
        val txn = mapped.first { it.externalId == "TRN-1" }
        assertEquals("Coffee Shop Co", txn.payee)
        assertEquals("latte", txn.memo)
        assertFalse(txn.pending)
    }

    @Test
    fun surfacesTopLevelErrors() {
        val accountSet = SimpleFinDecoder.decode(FIXTURE_WITH_ERRORS)
        assertEquals(listOf("Please reauthenticate at bridge.simplefin.org"), accountSet.errors)
        assertTrue(accountSet.accounts.isEmpty())
    }

    @Test
    fun decodesEmptyAccountsList() {
        val accountSet = SimpleFinDecoder.decode(FIXTURE_EMPTY_ACCOUNTS)
        assertTrue(accountSet.accounts.isEmpty())
        assertTrue(accountSet.errors.isEmpty())
    }

    @Test
    fun decodesMultipleAccountsAndTransactions() {
        val accountSet = SimpleFinDecoder.decode(FIXTURE_MULTI_ACCOUNT)
        assertEquals(2, accountSet.accounts.size)
        val checking = accountSet.accounts.first { it.id == "ACT-AAA" }
        val savings = accountSet.accounts.first { it.id == "ACT-BBB" }
        assertEquals(2, checking.transactions.size)
        assertEquals(1, savings.transactions.size)

        val checkingMapped = SimpleFinMapper.toMappedTransactions(checking)
        assertEquals(-1000L, checkingMapped.first { it.externalId == "TRN-A1" }.amountMinor)
        assertEquals(2000L, checkingMapped.first { it.externalId == "TRN-A2" }.amountMinor)

        val savingsMapped = SimpleFinMapper.toMappedTransactions(savings)
        assertEquals(50000L, savingsMapped.single().amountMinor)
    }
}
