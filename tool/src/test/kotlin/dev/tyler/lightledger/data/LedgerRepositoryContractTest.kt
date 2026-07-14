package dev.tyler.lightledger.data

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LedgerRepositoryContractTest {
    @Test fun ensureSeededCreatesEightDefaultCategories() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        assertEquals(8, repo.listCategories().size)
        assertEquals("Groceries", repo.listCategories().first().name)
    }

    @Test fun ensureSeededIsIdempotent() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        repo.ensureSeeded()
        assertEquals(8, repo.listCategories().size)
    }

    @Test fun addManualTransactionIsRetrievable() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        val id = repo.addManualTransaction(amountMinor = -450L, payee = "Coffee Shop", categoryId = category.id)
        val stored = repo.getTransaction(id)
        assertNotNull(stored)
        assertEquals(-450L, stored.amountMinor)
        assertEquals("Coffee Shop", stored.payee)
    }

    @Test fun monthSummarySumsByCategory() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val groceries = repo.listCategories().first { it.name == "Groceries" }
        repo.addManualTransaction(amountMinor = -1000L, payee = "Store A", categoryId = groceries.id)
        repo.addManualTransaction(amountMinor = -500L, payee = "Store B", categoryId = groceries.id)
        val summary = repo.monthSummary(YearMonth.now())
        assertEquals(-1500L, summary.first { it.categoryId == groceries.id }.totalMinor)
    }

    @Test fun deleteTransactionRemovesIt() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        val id = repo.addManualTransaction(amountMinor = -200L, payee = "Test", categoryId = category.id)
        repo.deleteTransaction(id)
        assertNull(repo.getTransaction(id))
    }

    @Test fun updateTransactionCategoryPersists() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val categories = repo.listCategories()
        val id = repo.addManualTransaction(amountMinor = -200L, payee = "Test", categoryId = categories[0].id)
        repo.updateTransactionCategory(id, categories[1].id)
        assertEquals(categories[1].id, repo.getTransaction(id)?.categoryId)
    }

    @Test fun archiveCategoryRemovesFromActiveList() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        repo.archiveCategory(category.id)
        assertTrue(repo.listCategories().none { it.id == category.id })
    }

    @Test fun archivingCategoryRetainsItsSpendInMonthSummary() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        repo.addManualTransaction(amountMinor = -450L, payee = "Coffee", categoryId = category.id)
        repo.archiveCategory(category.id)

        val summary = repo.monthSummary(YearMonth.now())
        assertEquals(-450L, summary.sumOf { it.totalMinor })
        assertTrue(repo.listCategories().none { it.id == category.id })
    }

    @Test fun needsReviewCountIsZeroForManualOnlyData() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        assertEquals(0, repo.needsReviewCount())
    }

    @Test fun upsertSimpleFinAccountInsertsThenUpdatesSameRow() = runTest {
        val repo = FakeLedgerRepository()
        val id1 = repo.upsertSimpleFinAccount(externalId = "acc-1", name = "Checking", currency = "USD")
        repo.insertExternalTransaction(
            accountId = id1,
            postedEpochDay = 100L,
            amountMinor = -100L,
            payee = "Test",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-1",
            pendingExternal = false,
            dedupHash = "hash-1",
        )

        val id2 = repo.upsertSimpleFinAccount(externalId = "acc-1", name = "Checking Renamed", currency = "USD")

        assertEquals(id1, id2)
        assertEquals("Checking Renamed", repo.listReviewInbox().first().accountName)
    }

    @Test fun insertExternalTransactionIsFindableByExternalId() = runTest {
        val repo = FakeLedgerRepository()
        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")

        val id = repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 100L,
            amountMinor = -500L,
            payee = "Coffee Shop",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-1",
            pendingExternal = false,
            dedupHash = "hash-1",
        )

        val found = repo.findTransactionByExternal(accountId, "txn-1")
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals(-500L, found.amountMinor)
        assertEquals(TransactionStatus.NEEDS_REVIEW, found.status)

        assertNull(repo.findTransactionByExternal(accountId, "no-such-txn"))
    }

    @Test fun confirmReviewSetsConfirmedAndCategoryAndDropsFromInbox() = runTest {
        val repo = FakeLedgerRepository()
        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        val category = repo.addCategory("Groceries")
        val id = repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 100L,
            amountMinor = -500L,
            payee = "Store",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-1",
            pendingExternal = false,
            dedupHash = "hash-1",
        )
        assertEquals(1, repo.listReviewInbox().size)

        repo.confirmReview(id, category.id)

        assertTrue(repo.listReviewInbox().isEmpty())
        val ref = repo.findTransactionByExternal(accountId, "txn-1")
        assertEquals(TransactionStatus.CONFIRMED, ref?.status)
        assertEquals(category.id, ref?.categoryId)
    }

    @Test fun listReviewInboxReturnsOnlyNeedsReviewNewestFirstWithAccountName() = runTest {
        val repo = FakeLedgerRepository()
        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        val category = repo.addCategory("Groceries")
        repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 100L,
            amountMinor = -100L,
            payee = "Old",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-old",
            pendingExternal = false,
            dedupHash = "hash-old",
        )
        val confirmedId = repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 150L,
            amountMinor = -200L,
            payee = "AlreadyConfirmed",
            memo = "",
            categoryId = category.id,
            status = TransactionStatus.CONFIRMED,
            externalId = "txn-confirmed",
            pendingExternal = false,
            dedupHash = "hash-confirmed",
        )
        repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 200L,
            amountMinor = -300L,
            payee = "New",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-new",
            pendingExternal = false,
            dedupHash = "hash-new",
        )

        val inbox = repo.listReviewInbox()

        assertEquals(2, inbox.size)
        assertEquals("New", inbox[0].payee)
        assertEquals("Old", inbox[1].payee)
        assertTrue(inbox.all { it.accountName == "Checking" })
        assertTrue(inbox.none { it.id == confirmedId })
    }

    @Test fun findDedupCandidatesReturnsRowsSharingHash() = runTest {
        val repo = FakeLedgerRepository()
        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        val id1 = repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 100L,
            amountMinor = -500L,
            payee = "Coffee",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-1",
            pendingExternal = false,
            dedupHash = "shared-hash",
        )
        repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 200L,
            amountMinor = -999L,
            payee = "Different",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-2",
            pendingExternal = false,
            dedupHash = "other-hash",
        )

        val candidates = repo.findDedupCandidates("shared-hash")

        assertEquals(1, candidates.size)
        assertEquals(id1, candidates.first().id)
    }

    @Test fun insertRuleAndListRulesRoundTrip() = runTest {
        val repo = FakeLedgerRepository()
        val category = repo.addCategory("Dining")

        repo.insertRule("STARBUCKS", category.id)
        val rules = repo.listRules()

        assertEquals(1, rules.size)
        assertEquals("starbucks", rules.first().payeeContains)
        assertEquals(category.id, rules.first().categoryId)
        assertTrue(rules.first().enabled)
    }

    @Test fun pastConfirmationsNormalizesPayeeAndOnlyIncludesConfirmedWithCategory() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        repo.addManualTransaction(amountMinor = -500L, payee = "Starbucks 12345", categoryId = category.id)
        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 100L,
            amountMinor = -600L,
            payee = "Uncategorized",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-1",
            pendingExternal = false,
            dedupHash = "hash-1",
        )

        val past = repo.pastConfirmations()

        assertEquals(1, past.size)
        assertEquals("starbucks", past.first().normalizedPayee)
        assertEquals(category.id, past.first().categoryId)
    }

    @Test fun deleteSimpleFinDataRemovesOnlySimpleFinAccountsAndTransactions() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        val manualId = repo.addManualTransaction(amountMinor = -100L, payee = "Manual", categoryId = category.id)

        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = 100L,
            amountMinor = -600L,
            payee = "External",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "txn-1",
            pendingExternal = false,
            dedupHash = "hash-1",
        )

        repo.deleteSimpleFinData()

        assertNotNull(repo.getTransaction(manualId))
        assertNull(repo.findTransactionByExternal(accountId, "txn-1"))
        assertEquals(0, repo.needsReviewCount())
    }

    @Test fun listStalePendingExternalReturnsOnlyAgedPendingRowsForTheAccount() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val category = repo.listCategories().first()
        val accountId = repo.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        val day = 1_000L
        val amount = -500L

        val pendingId = repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = day - 10,
            amountMinor = amount,
            payee = "Pending Store",
            memo = "",
            categoryId = 7L,
            status = TransactionStatus.CONFIRMED,
            externalId = "OLD",
            pendingExternal = true,
            dedupHash = "hash-old",
        )
        val settledId = repo.insertExternalTransaction(
            accountId = accountId,
            postedEpochDay = day - 9,
            amountMinor = amount,
            payee = "Settled Store",
            memo = "",
            categoryId = null,
            status = TransactionStatus.NEEDS_REVIEW,
            externalId = "NEW",
            pendingExternal = false,
            dedupHash = "hash-new",
        )
        // Unrelated MANUAL row: different account (the seeded manual account) and today's date,
        // far outside the day window below — must never surface in either query.
        val manualId = repo.addManualTransaction(amountMinor = amount, payee = "Manual", categoryId = category.id)

        val stale = repo.listStalePendingExternal(accountId, day - 5)
        assertEquals(1, stale.size)
        assertEquals(pendingId, stale.first().id)
        assertEquals("OLD", stale.first().externalId)
        assertEquals(7L, stale.first().categoryId)
        assertTrue(stale.none { it.id == settledId || it.id == manualId })

        val matches = repo.findSettledMatches(accountId, amount, day - 13, day - 5)
        assertEquals(1, matches.size)
        assertEquals(settledId, matches.first().id)
        assertEquals("NEW", matches.first().externalId)
        assertTrue(matches.none { it.id == pendingId || it.id == manualId })

        repo.deleteTransaction(pendingId)
        assertNull(repo.getTransaction(pendingId))
        assertTrue(repo.listStalePendingExternal(accountId, day - 5).isEmpty())
        assertNotNull(repo.getTransaction(settledId))
    }
}
