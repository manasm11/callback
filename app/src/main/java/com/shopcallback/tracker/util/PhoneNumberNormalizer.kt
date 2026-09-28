package com.shopcallback.tracker.util

object PhoneNumberNormalizer {
    fun normalize(rawNumber: String): String {
        val digitsOnly = rawNumber.filter { it.isDigit() }
        return if (digitsOnly.length > 10) digitsOnly.takeLast(10) else digitsOnly
    }
}
