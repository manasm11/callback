package com.shopcallback.tracker.ui

import com.shopcallback.tracker.data.ResolvedReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedReasonTextTest {
    @Test
    fun `each reason has its History label`() {
        assertEquals("answered", resolvedReasonText(ResolvedReason.AUTO_ANSWERED))
        assertEquals("marked resolved", resolvedReasonText(ResolvedReason.MANUAL))
        assertEquals("answered on another phone", resolvedReasonText(ResolvedReason.REMOTE_ANSWERED))
        assertEquals("marked resolved on another phone", resolvedReasonText(ResolvedReason.REMOTE_MANUAL))
    }
}
