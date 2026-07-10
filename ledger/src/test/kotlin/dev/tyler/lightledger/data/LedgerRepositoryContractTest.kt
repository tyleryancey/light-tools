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

    @Test fun needsReviewCountIsZeroForManualOnlyData() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        assertEquals(0, repo.needsReviewCount())
    }
}
