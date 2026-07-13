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

// A real (anonymized) SimpleFIN Bridge /accounts response, provided from a live subscription.
// Exercises the full real-world shape the lean models must tolerate via ignoreUnknownKeys:
// a top-level "x-api-message" hint, account-level balance/available-balance/balance-date/
// holdings/org objects, 9 of 10 accounts with empty transactions, and on the one transaction
// the un-modeled "transacted_at" and a null "mcc". Locks in that a real payload decodes and
// that the single transaction maps to payee (not description) with the correct signed minor amount.
private val FIXTURE_REAL_WORLD_ACCOUNTS = """
{
  "errors": [],
  "accounts": [
    {"id": "ACT-26a5cfbf-014c-4d0b-acde-d694b8fc3a43", "name": "HELOC Account 1", "currency": "USD", "balance": "0.00", "available-balance": "0.00", "balance-date": 1783879664, "transactions": [], "holdings": [], "org": {"domain": "example1.com", "name": "Financial Institution 1", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example1.com/", "id": "example1.com"}},
    {"id": "ACT-01b3ae7b-9649-49e8-963a-29b49425a9e6", "name": "HELOC Account 2", "currency": "USD", "balance": "0.00", "available-balance": "0.00", "balance-date": 1783939899, "transactions": [], "holdings": [], "org": {"domain": "example1.com", "name": "Financial Institution 1", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example1.com/", "id": "example1.com"}},
    {"id": "ACT-14424d43-3ea5-484b-901a-0e42ef59a5af", "name": "Primary Checking", "currency": "USD", "balance": "14468.07", "available-balance": "2306.99", "balance-date": 1783910942,
      "transactions": [{"id": "TRN-79e69536-d5e1-46aa-b20d-053f62f0b207", "posted": 1783875392, "amount": "-2496.04", "description": "ACH Transfer", "payee": "Merchant B", "memo": "ACH Transfer", "transacted_at": 1783864936, "mcc": null}],
      "holdings": [], "org": {"domain": "example1.com", "name": "Financial Institution 1", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example1.com/", "id": "example1.com"}},
    {"id": "ACT-5c111552-c062-49f5-bf1f-5e740d652a7b", "name": "Share Savings", "currency": "USD", "balance": "2.77", "available-balance": "0.05", "balance-date": 1783887791, "transactions": [], "holdings": [], "org": {"domain": "example1.com", "name": "Financial Institution 1", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example1.com/", "id": "example1.com"}},
    {"id": "ACT-b98d4707-d3ef-4026-a3c6-3b8b1718bbe2", "name": "Online Savings", "currency": "USD", "balance": "297.81", "available-balance": "45.60", "balance-date": 1783942303, "transactions": [], "holdings": [], "org": {"domain": "example2.com", "name": "Financial Institution 2", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example2.com/", "id": "example2.com"}},
    {"id": "ACT-3982df3c-d11c-4646-98fa-35427ff54a37", "name": "Personal Credit Card", "currency": "USD", "balance": "-722.13", "available-balance": "-7857.30", "balance-date": 1783947553, "transactions": [], "holdings": [], "org": {"domain": "example3.com", "name": "Financial Institution 3", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example3.com/", "id": "example3.com"}},
    {"id": "ACT-d185bd99-24ca-48d9-af74-f91376a16419", "name": "Rewards Credit Card", "currency": "USD", "balance": "-222.34", "available-balance": "0.00", "balance-date": 1783930459, "transactions": [], "holdings": [], "org": {"domain": "example4.com", "name": "Financial Institution 4", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example4.com/", "id": "example4.com"}},
    {"id": "ACT-98c39601-edd8-4cec-90f0-ac6535d976ca", "name": "Retail Rewards Visa", "currency": "USD", "balance": "-348.48", "available-balance": "0.00", "balance-date": 1783931252, "transactions": [], "holdings": [], "org": {"domain": "example4.com", "name": "Financial Institution 4", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example4.com/", "id": "example4.com"}},
    {"id": "ACT-2f7ea06a-3c17-45cd-8127-21661323429e", "name": "Retail Store Card", "currency": "USD", "balance": "-167.63", "available-balance": "0.00", "balance-date": 1783929799, "transactions": [], "holdings": [], "org": {"domain": "example5.com", "name": "Financial Institution 5", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example5.com/", "id": "example5.com"}},
    {"id": "ACT-82435d04-2e39-4d36-95ee-53f33f04d74f", "name": "Mortgage Loan", "currency": "USD", "balance": "-307651.35", "available-balance": "0.00", "balance-date": 1783910111, "transactions": [], "holdings": [], "org": {"domain": "example6.com", "name": "Financial Institution 6", "sfin-url": "https://bridge.example.com/simplefin", "url": "https://example6.com/", "id": "example6.com"}}
  ],
  "x-api-message": ["Provide a 'start-date' parameter to receive transactions prior to yesterday"]
}
""".trimIndent()

class SimpleFinDecodeTest {

    @Test
    fun decodesRealWorldPayloadWithoutFailing() {
        val accountSet = SimpleFinDecoder.decode(FIXTURE_REAL_WORLD_ACCOUNTS)
        // Top-level "x-api-message" and per-account balance/holdings/org are all unmodeled
        // and must be tolerated, not cause a decode failure.
        assertTrue(accountSet.errors.isEmpty())
        assertEquals(10, accountSet.accounts.size)

        val checking = accountSet.accounts.first { it.name == "Primary Checking" }
        assertEquals("ACT-14424d43-3ea5-484b-901a-0e42ef59a5af", checking.id)
        assertEquals("USD", checking.currency)
        assertEquals(1, checking.transactions.size)

        // The one transaction carries the un-modeled "transacted_at" and a null "mcc" — both ignored.
        val txn = checking.transactions.single()
        assertEquals("TRN-79e69536-d5e1-46aa-b20d-053f62f0b207", txn.id)
        assertEquals("-2496.04", txn.amount)
        assertEquals("Merchant B", txn.payee)
        assertFalse(txn.pending) // absent in payload -> defaults false

        // The nine non-transaction accounts decode to empty transaction lists.
        assertEquals(9, accountSet.accounts.count { it.transactions.isEmpty() })
    }

    @Test
    fun mapsRealWorldTransactionPreferringPayeeAndSignedMinorAmount() {
        val checking = SimpleFinDecoder.decode(FIXTURE_REAL_WORLD_ACCOUNTS)
            .accounts.first { it.name == "Primary Checking" }
        val mapped = SimpleFinMapper.toMappedTransactions(checking)
        assertEquals(1, mapped.size)
        val txn = mapped.single()

        assertEquals("TRN-79e69536-d5e1-46aa-b20d-053f62f0b207", txn.externalId)
        // "-2496.04" USD -> minor units, spend stays negative.
        assertEquals(-249604L, txn.amountMinor)
        // payee present -> preferred over description ("ACH Transfer").
        assertEquals("Merchant B", txn.payee)
        assertEquals("ACH Transfer", txn.memo)
        assertFalse(txn.pending)

        // posted (unix seconds) -> device-local epochDay, re-derived with the mapper's own chain
        // so the assertion is time-zone-independent.
        val expectedEpochDay = Instant.ofEpochSecond(1783875392L)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        assertEquals(expectedEpochDay, txn.postedEpochDay)
    }

    @Test
    fun mapsRealWorldEmptyTransactionAccountToEmptyList() {
        val heloc = SimpleFinDecoder.decode(FIXTURE_REAL_WORLD_ACCOUNTS)
            .accounts.first { it.name == "HELOC Account 1" }
        assertTrue(SimpleFinMapper.toMappedTransactions(heloc).isEmpty())
    }

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
