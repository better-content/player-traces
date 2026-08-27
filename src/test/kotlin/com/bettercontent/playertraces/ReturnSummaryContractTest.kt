package com.bettercontent.playertraces

import com.bettercontent.playertraces.api.ReturnSummary
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReturnSummaryContractTest {
    @Test fun reportsOnlyPositiveEvidence() {
        assertFalse(ReturnSummary(0, 0, 0, 0).hasChanges())
        assertTrue(ReturnSummary(1, 0, 0, 0).hasChanges())
        assertTrue(ReturnSummary(0, 1, 1, 1).hasChanges())
    }
}
