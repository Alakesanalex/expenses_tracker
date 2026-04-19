package com.example.myapplication.util

import java.util.*

data class ScannedData(
    val title: String?,
    val amount: Double?,
    val date: Long?,
    val category: String?
)

class DataParser {
    private val expenseCategories = mapOf(
        "Food" to listOf("restaurant", "cafe", "food", "grocery", "market", "pizza", "burger", "dinner", "lunch", "breakfast", "eat", "bakery", "hotel"),
        "Transport" to listOf("uber", "ola", "taxi", "fuel", "petrol", "diesel", "metro", "train", "bus", "flight", "parking"),
        "Shopping" to listOf("mall", "store", "amazon", "flipkart", "clothing", "fashion", "electronics", "mart", "supermarket"),
        "Entertainment" to listOf("movie", "cinema", "netflix", "theatre", "gaming", "club", "bar"),
        "Utilities" to listOf("bill", "electricity", "water", "gas", "recharge", "internet", "wifi", "mobile")
    )

    fun parse(text: String): ScannedData {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val fullTextLower = text.lowercase()

        // 1. Extract Merchant/Title (usually the first few lines)
        val title = lines.take(3).find { it.any { c -> c.isLetter() } }

        // 2. Extract Amount
        val amount = extractAmount(text)

        // 3. Extract Date
        val date = extractDate(text)

        // 4. Detect Category
        val category = detectCategory(fullTextLower)

        return ScannedData(title, amount, date, category)
    }

    private fun extractAmount(text: String): Double? {
        // Look for keywords and then numbers near them
        val totalKeywords = listOf("total", "amount", "net", "payable", "sum", "grand total")
        val lines = text.split("\n")
        
        // Try finding lines with "total" and extracting decimal
        for (line in lines) {
            val lowerLine = line.lowercase()
            if (totalKeywords.any { lowerLine.contains(it) }) {
                val match = Regex("""\d+[.,]\d{2}""").find(line)
                match?.value?.replace(",", ".")?.toDoubleOrNull()?.let { return it }
            }
        }

        // Fallback: Largest decimal value
        val decimalRegex = Regex("""\d+[.,]\d{2}""")
        val decimalMatches = decimalRegex.findAll(text)
        return decimalMatches.map { it.value.replace(",", ".").toDoubleOrNull() }
            .filterNotNull()
            .maxOrNull()
    }

    private fun extractDate(text: String): Long? {
        val dateRegex = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""")
        val match = dateRegex.find(text)?.value ?: return null
        
        return try {
            val cleanDate = match.replace("-", "/")
            val parts = cleanDate.split("/")
            val calendar = Calendar.getInstance()
            val day = parts[0].toInt()
            val month = parts[1].toInt() - 1
            val yearStr = parts[2]
            val year = if (yearStr.length == 2) 2000 + yearStr.toInt() else yearStr.toInt()
            calendar.set(year, month, day)
            calendar.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    private fun detectCategory(text: String): String? {
        for ((category, keywords) in expenseCategories) {
            if (keywords.any { text.contains(it) }) {
                return category
            }
        }
        return null
    }
}
