package com.example.myapplication.util

import android.util.Log
import java.util.*

data class ScannedData(
    val title: String,
    val amount: Double?,
    val date: Long,
    val category: String
)

/**
 * Highly robust Data Parser for OCR text extraction from receipts using Google ML Kit.
 * Optimized for various receipt formats and common OCR errors.
 */
class DataParser {
    private val TAG = "DataParser"
    
    private val expenseCategories = mapOf(
        "Food" to listOf("restaurant", "cafe", "food", "grocery", "market", "pizza", "burger", "dinner", "lunch", "breakfast", "eat", "bakery", "hotel", "swiggy", "zomato", "cucumber", "grape", "fruit", "salad", "veg", "pappaya", "dragon fruit", "farming", "bakes", "sweets", "juice", "ice cream", "dining", "provision"),
        "Transport" to listOf("uber", "ola", "taxi", "fuel", "petrol", "diesel", "metro", "train", "bus", "flight", "parking", "shell", "hpcl", "bpcl"),
        "Shopping" to listOf("mall", "store", "amazon", "flipkart", "clothing", "fashion", "electronics", "mart", "supermarket", "reliance", "dmart", "lifestyle", "trends", "apparel"),
        "Entertainment" to listOf("movie", "cinema", "netflix", "theatre", "gaming", "club", "bar", "bookmyshow"),
        "Utilities" to listOf("bill", "electricity", "water", "gas", "recharge", "internet", "wifi", "mobile", "jio", "airtel", "vi")
    )

    fun parse(text: String): ScannedData {
        Log.d(TAG, "Parsing OCR text. Full text:\n$text")
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val fullTextLower = text.lowercase()

        // 1. Extract Merchant Name
        val title = extractTitle(lines)

        // 2. Extract Total Amount
        val amount = extractAmount(text)

        // 3. Extract Date
        val date = extractDate(text) ?: System.currentTimeMillis()

        // 4. Detect Category
        val category = detectCategory(fullTextLower) ?: "Other"

        return ScannedData(title, amount, date, category)
    }

    private fun extractTitle(lines: List<String>): String {
        val keywords = listOf("Pvt Ltd", "Ltd", "Farming", "Store", "Bakes", "Ventures", "Bakery", "Mart", "Cafe", "Restaurant")
        return lines.take(8).find { line ->
            keywords.any { line.contains(it, true) } ||
            (line.length > 5 && line.any { it.isLetter() } && 
             !line.contains("Date", true) && !line.contains("Time", true) && 
             !line.contains("Invoice", true) && !line.contains("Bill", true) && 
             !line.contains("PH NO", true) && !line.contains("GST", true))
        } ?: lines.firstOrNull { it.any { it.isLetter() } } ?: "Scanned Receipt"
    }

    private fun extractAmount(text: String): Double? {
        val lines = text.split("\n")
        val totalKeywords = listOf("total", "net amt", "grand total", "payable", "balance", "gpay", "paid", "amount", "sum", "wallet", "cash", "amt", "net total")
        
        val candidates = mutableListOf<Pair<Double, Int>>() // Value and Score

        // Pattern for numbers like 248.00, 1,248.50, 248
        val numberRegex = Regex("""\d{1,3}(?:[.,\s]\d{3})*[.,]\d{2}|\d+""")

        for (i in lines.indices) {
            val line = lines[i]
            val lowerLine = line.lowercase()
            val hasKeyword = totalKeywords.any { lowerLine.contains(it) }

            val matches = numberRegex.findAll(line).toList()
            for (match in matches) {
                val value = parseNumber(match.value) ?: continue
                if (value <= 0 || value > 500000) continue

                var score = 0
                if (hasKeyword) score += 60
                
                // If the number is at the very end of the line, it's more likely a total
                if (match.range.last >= line.length - 4) score += 20
                
                // Numbers with decimals are more likely to be prices
                if (match.value.contains(".") || match.value.contains(",")) score += 10
                
                candidates.add(value to score)
            }

            // Case: Keyword on one line, value on the next line
            if (hasKeyword && matches.isEmpty() && i + 1 < lines.size) {
                val nextLineMatches = numberRegex.findAll(lines[i+1]).toList()
                if (nextLineMatches.isNotEmpty()) {
                    val value = parseNumber(nextLineMatches.first().value)
                    if (value != null && value > 0) candidates.add(value to 40)
                }
            }
        }

        if (candidates.isNotEmpty()) {
            // Pick highest score, then highest value
            val best = candidates.sortedWith(compareByDescending<Pair<Double, Int>> { it.second }.thenByDescending { it.first }).first()
            Log.d(TAG, "Amount detected with score ${best.second}: ${best.first}")
            return best.first
        }

        // Final Fallback: Largest decimal value in the whole text
        val allNumbers = numberRegex.findAll(text)
            .map { parseNumber(it.value) }
            .filterNotNull()
            .filter { it > 1.0 && it < 100000.0 }
            .toList()

        return allNumbers.maxOrNull()
    }

    private fun parseNumber(s: String): Double? {
        // Remove spaces and currency symbols
        val clean = s.replace("[^\\d.,]".toRegex(), "")
        if (clean.isEmpty()) return null
        
        // Handle decimals
        return if (clean.contains(".") || clean.contains(",")) {
            val lastDot = clean.lastIndexOf('.')
            val lastComma = clean.lastIndexOf(',')
            val separatorIdx = Math.max(lastDot, lastComma)
            
            // If the separator is near the end, treat as decimal
            if (separatorIdx >= clean.length - 3) {
                val whole = clean.substring(0, separatorIdx).replace(".", "").replace(",", "")
                val decimal = clean.substring(separatorIdx + 1)
                "$whole.$decimal".toDoubleOrNull()
            } else {
                // Otherwise treat as thousand separator
                clean.replace(".", "").replace(",", "").toDoubleOrNull()
            }
        } else {
            clean.toDoubleOrNull()
        }
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
            
            val year = when (yearStr.length) {
                2 -> if (yearStr.toInt() > 70) 1900 + yearStr.toInt() else 2000 + yearStr.toInt()
                4 -> yearStr.toInt()
                else -> 2000 + yearStr.toInt()
            }
            
            calendar.set(year, month, day, 12, 0, 0)
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
