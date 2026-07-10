package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {
    @Test fun matchesCaseInsensitiveContains() {
        val rules = listOf(CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 10L, enabled = true))
        assertEquals(10L, RuleEngine.matchCategory(rules, "STARBUCKS #123"))
    }

    @Test fun noMatchReturnsNull() {
        val rules = listOf(CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 10L, enabled = true))
        assertNull(RuleEngine.matchCategory(rules, "Peets Coffee"))
    }

    @Test fun disabledRuleIsIgnored() {
        val rules = listOf(CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 10L, enabled = false))
        assertNull(RuleEngine.matchCategory(rules, "Starbucks"))
    }

    @Test fun firstEnabledMatchWins() {
        val rules = listOf(
            CategoryRule(id = 1L, payeeContains = "coffee", categoryId = 10L, enabled = true),
            CategoryRule(id = 2L, payeeContains = "starbucks", categoryId = 20L, enabled = true),
        )
        assertEquals(10L, RuleEngine.matchCategory(rules, "Starbucks Coffee"))
    }

    @Test fun shouldCreateRuleAtThreshold() {
        val confirmations = List(3) { PastConfirmation("starbucks", 10L) }
        assertTrue(RuleEngine.shouldCreateRule(confirmations, "starbucks", 10L))
    }

    @Test fun shouldNotCreateRuleBelowThreshold() {
        val confirmations = List(2) { PastConfirmation("starbucks", 10L) }
        assertFalse(RuleEngine.shouldCreateRule(confirmations, "starbucks", 10L))
    }

    @Test fun differentCategoryDoesNotCountTowardThreshold() {
        val confirmations = listOf(
            PastConfirmation("starbucks", 10L),
            PastConfirmation("starbucks", 20L),
            PastConfirmation("starbucks", 10L),
        )
        assertFalse(RuleEngine.shouldCreateRule(confirmations, "starbucks", 10L))
    }
}
