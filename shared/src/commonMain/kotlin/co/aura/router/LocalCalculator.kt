package co.aura.router

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Deterministic local math, percentage, conversion, and date arithmetic engine.
 * Safe, zero-eval, zero-token local computation.
 */
object LocalCalculator {

    private val numberFormat = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))

    fun formatNumber(value: Double): String {
        return if (value == value.roundToLong().toDouble()) {
            value.roundToLong().toString()
        } else {
            val rounded = (value * 100.0).roundToLong() / 100.0
            if (rounded == rounded.roundToLong().toDouble()) {
                rounded.roundToLong().toString()
            } else {
                rounded.toString()
            }
        }
    }

    /**
     * Checks if the given input is a mathematical / conversion query that can be resolved locally.
     */
    fun canHandle(query: String): Boolean {
        val lower = query.lowercase().trim()
            .removePrefix("calculate")
            .removePrefix("what is")
            .removePrefix("whats")
            .removePrefix("how much is")
            .removePrefix("can you calculate")
            .removePrefix("tell me")
            .trim()

        if (lower.isBlank()) return false

        // Percentage patterns
        if (Regex("""\b\d+(\.\d+)?\s*(%|percent)\s*(of|off)\s*\d+(\.\d+)?\b""").containsMatchIn(lower)) return true

        // Average patterns
        if (lower.startsWith("average of") || lower.startsWith("mean of")) return true

        // Ratio patterns
        if (Regex("""\bratio of\s+\d+(\.\d+)?\s+(to|and)\s+\d+(\.\d+)?\b""").containsMatchIn(lower)) return true

        // Unit conversion patterns
        if (isUnitConversion(lower)) return true

        // Date difference patterns
        if (isDateDifference(lower)) return true

        // Basic arithmetic with numbers and operators or words
        if (isArithmeticExpression(lower)) return true

        return false
    }

    /**
     * Evaluates a mathematical, unit conversion, or date difference query.
     * Returns null if the query cannot be safely calculated.
     */
    fun evaluate(query: String): String? {
        val lower = query.lowercase().trim()
            .removePrefix("calculate")
            .removePrefix("what is")
            .removePrefix("whats")
            .removePrefix("how much is")
            .removePrefix("can you calculate")
            .removePrefix("tell me")
            .trim()
            .removeSuffix("?")
            .removeSuffix(".")

        return try {
            when {
                // 1. Percentage
                Regex("""(\d+(\.\d+)?)\s*(%|percent)\s+off\s+(\d+(\.\d+)?)""").find(lower) != null -> {
                    val match = Regex("""(\d+(\.\d+)?)\s*(%|percent)\s+off\s+(\d+(\.\d+)?)""").find(lower) ?: return null
                    val pct = match.groupValues[1].toDouble()
                    val base = match.groupValues[4].toDouble()
                    val discount = base * (pct / 100.0)
                    val result = base - discount
                    "${formatNumber(pct)}% off ${formatNumber(base)} is ${formatNumber(result)}, sir. (You save ${formatNumber(discount)})"
                }

                Regex("""(\d+(\.\d+)?)\s*(%|percent)\s+of\s+(\d+(\.\d+)?)""").find(lower) != null -> {
                    val match = Regex("""(\d+(\.\d+)?)\s*(%|percent)\s+of\s+(\d+(\.\d+)?)""").find(lower) ?: return null
                    val pct = match.groupValues[1].toDouble()
                    val base = match.groupValues[4].toDouble()
                    val result = base * (pct / 100.0)
                    "${formatNumber(pct)} percent of ${formatNumber(base)} is ${formatNumber(result)}, sir."
                }

                // 2. Average / Mean
                lower.startsWith("average of") || lower.startsWith("mean of") -> {
                    val numbersStr = lower.removePrefix("average of").removePrefix("mean of").replace("and", ",")
                    val numbers = numbersStr.split(Regex("[,\\s]+"))
                        .mapNotNull { it.trim().toDoubleOrNull() }
                    if (numbers.isNotEmpty()) {
                        val avg = numbers.average()
                        "The average is ${formatNumber(avg)}, sir."
                    } else null
                }

                // 3. Ratio
                Regex("""ratio of\s+(\d+(\.\d+)?)\s+(to|and)\s+(\d+(\.\d+)?)""").find(lower) != null -> {
                    val match = Regex("""ratio of\s+(\d+(\.\d+)?)\s+(to|and)\s+(\d+(\.\d+)?)""").find(lower) ?: return null
                    val a = match.groupValues[1].toDouble()
                    val b = match.groupValues[4].toDouble()
                    if (b == 0.0) {
                        "Sir, division by zero is undefined."
                    } else {
                        val gcd = findGcd(a.roundToLong(), b.roundToLong())
                        if (gcd > 0 && a == a.roundToLong().toDouble() && b == b.roundToLong().toDouble()) {
                            val simpA = a.roundToLong() / gcd
                            val simpB = b.roundToLong() / gcd
                            "The ratio of ${formatNumber(a)} to ${formatNumber(b)} is $simpA:$simpB (or ${formatNumber(a / b)}), sir."
                        } else {
                            "The ratio of ${formatNumber(a)} to ${formatNumber(b)} is ${formatNumber(a / b)}, sir."
                        }
                    }
                }

                // 4. Unit Conversion
                isUnitConversion(lower) -> {
                    evaluateUnitConversion(lower)
                }

                // 5. Date Difference
                isDateDifference(lower) -> {
                    evaluateDateDifference(lower)
                }

                // 6. Arithmetic
                isArithmeticExpression(lower) -> {
                    evaluateArithmetic(lower)
                }

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isUnitConversion(lower: String): Boolean {
        val units = listOf("km", "kilometer", "kilometers", "kilometres", "metre", "metres", "meter", "meters", "m",
            "mile", "miles", "mi", "foot", "feet", "ft", "inch", "inches", "cm", "centimeter", "centimeters",
            "kg", "kilogram", "kilograms", "kilo", "kilos", "gram", "grams", "g", "pound", "pounds", "lbs", "lb",
            "celsius", "fahrenheit", "liter", "liters", "litres", "litre", "l", "gallon", "gallons", "gal")
        return (lower.startsWith("convert ") || lower.contains(" to ") || lower.contains(" in ")) &&
                units.any { lower.contains(it) } &&
                Regex("""\d+""").containsMatchIn(lower)
    }

    private fun evaluateUnitConversion(input: String): String? {
        val cleaned = input.removePrefix("convert").trim()
        val regex = Regex("""(\d+(\.\d+)?)\s*([a-zA-Z]+)\s*(to|in|into)\s*([a-zA-Z]+)""")
        val match = regex.find(cleaned) ?: return null

        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val fromUnit = match.groupValues[3].lowercase()
        val toUnit = match.groupValues[5].lowercase()

        return when {
            // Distance / Length
            (fromUnit in listOf("km", "kilometer", "kilometers", "kilometres")) && (toUnit in listOf("m", "meter", "meters", "metres")) ->
                "${formatNumber(value)} km is ${formatNumber(value * 1000.0)} meters, sir."
            (fromUnit in listOf("m", "meter", "meters", "metres")) && (toUnit in listOf("km", "kilometer", "kilometers", "kilometres")) ->
                "${formatNumber(value)} meters is ${formatNumber(value / 1000.0)} km, sir."
            (fromUnit in listOf("mile", "miles", "mi")) && (toUnit in listOf("km", "kilometer", "kilometers", "kilometres")) ->
                "${formatNumber(value)} miles is ${formatNumber(value * 1.60934)} km, sir."
            (fromUnit in listOf("km", "kilometer", "kilometers", "kilometres")) && (toUnit in listOf("mile", "miles", "mi")) ->
                "${formatNumber(value)} km is ${formatNumber(value / 1.60934)} miles, sir."
            (fromUnit in listOf("m", "meter", "meters")) && (toUnit in listOf("feet", "foot", "ft")) ->
                "${formatNumber(value)} meters is ${formatNumber(value * 3.28084)} feet, sir."
            (fromUnit in listOf("feet", "foot", "ft")) && (toUnit in listOf("m", "meter", "meters")) ->
                "${formatNumber(value)} feet is ${formatNumber(value / 3.28084)} meters, sir."
            (fromUnit in listOf("inch", "inches")) && (toUnit in listOf("cm", "centimeter", "centimeters")) ->
                "${formatNumber(value)} inches is ${formatNumber(value * 2.54)} cm, sir."
            (fromUnit in listOf("cm", "centimeter", "centimeters")) && (toUnit in listOf("inch", "inches")) ->
                "${formatNumber(value)} cm is ${formatNumber(value / 2.54)} inches, sir."

            // Mass / Weight
            (fromUnit in listOf("kg", "kilogram", "kilograms", "kilos", "kilo")) && (toUnit in listOf("g", "gram", "grams")) ->
                "${formatNumber(value)} kg is ${formatNumber(value * 1000.0)} grams, sir."
            (fromUnit in listOf("g", "gram", "grams")) && (toUnit in listOf("kg", "kilogram", "kilograms", "kilos")) ->
                "${formatNumber(value)} grams is ${formatNumber(value / 1000.0)} kg, sir."
            (fromUnit in listOf("kg", "kilogram", "kilograms", "kilos")) && (toUnit in listOf("lb", "lbs", "pound", "pounds")) ->
                "${formatNumber(value)} kg is ${formatNumber(value * 2.20462)} pounds, sir."
            (fromUnit in listOf("lb", "lbs", "pound", "pounds")) && (toUnit in listOf("kg", "kilogram", "kilograms", "kilos")) ->
                "${formatNumber(value)} pounds is ${formatNumber(value / 2.20462)} kg, sir."

            // Temperature
            (fromUnit in listOf("celsius", "c")) && (toUnit in listOf("fahrenheit", "f")) ->
                "${formatNumber(value)}°C is ${formatNumber((value * 9.0 / 5.0) + 32.0)}°F, sir."
            (fromUnit in listOf("fahrenheit", "f")) && (toUnit in listOf("celsius", "c")) ->
                "${formatNumber(value)}°F is ${formatNumber((value - 32.0) * 5.0 / 9.0)}°C, sir."

            // Volume
            (fromUnit in listOf("liter", "liters", "litre", "litres", "l")) && (toUnit in listOf("ml", "milliliters", "millilitres")) ->
                "${formatNumber(value)} liters is ${formatNumber(value * 1000.0)} ml, sir."
            (fromUnit in listOf("gallon", "gallons", "gal")) && (toUnit in listOf("liter", "liters", "litre", "litres", "l")) ->
                "${formatNumber(value)} gallons is ${formatNumber(value * 3.78541)} liters, sir."
            (fromUnit in listOf("liter", "liters", "litre", "litres", "l")) && (toUnit in listOf("gallon", "gallons", "gal")) ->
                "${formatNumber(value)} liters is ${formatNumber(value / 3.78541)} gallons, sir."

            else -> null
        }
    }

    private fun isDateDifference(lower: String): Boolean {
        return lower.contains("days until") || lower.contains("days left until") ||
                lower.contains("days between") || lower.contains("days since") ||
                lower.contains("how many days until") || lower.contains("how many days between")
    }

    private fun evaluateDateDifference(lower: String): String? {
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)

        when {
            lower.contains("christmas") -> {
                val target = Calendar.getInstance().apply {
                    set(currentYear, Calendar.DECEMBER, 25, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(now)) add(Calendar.YEAR, 1)
                }
                val diffDays = ((target.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                return "There are $diffDays days until Christmas, sir."
            }

            lower.contains("new year") -> {
                val target = Calendar.getInstance().apply {
                    set(currentYear + 1, Calendar.JANUARY, 1, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffDays = ((target.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                return "There are $diffDays days until the New Year, sir."
            }

            lower.contains("halloween") -> {
                val target = Calendar.getInstance().apply {
                    set(currentYear, Calendar.OCTOBER, 31, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(now)) add(Calendar.YEAR, 1)
                }
                val diffDays = ((target.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                return "There are $diffDays days until Halloween, sir."
            }

            else -> return null
        }
    }

    private fun isArithmeticExpression(lower: String): Boolean {
        // Words indicating arithmetic
        val mathWords = listOf("multiplied by", "multiply", "times", "divided by", "divide", "plus", "minus", "add", "subtract", "to the power of", "^")
        val hasWord = mathWords.any { lower.contains(it) }
        val hasOperator = Regex("""\d+\s*[\+\-\*\/\^]\s*\d+""").containsMatchIn(lower)
        return (hasWord || hasOperator) && Regex("""\d+""").containsMatchIn(lower)
    }

    private fun evaluateArithmetic(input: String): String? {
        // Normalize verbal arithmetic words to symbols
        var expr = input
            .replace("multiplied by", "*")
            .replace("multiply", "*")
            .replace("times", "*")
            .replace("divided by", "/")
            .replace("divide by", "/")
            .replace("divide", "/")
            .replace("to the power of", "^")
            .replace("plus", "+")
            .replace("add", "+")
            .replace("minus", "-")
            .replace("subtract", "-")
            .replace("x", "*")
            .replace("into", "*")

        // Handle "add A and B" -> "A + B"
        val addAndMatch = Regex("""\+\s*(\d+(\.\d+)?)\s*and\s*(\d+(\.\d+)?)""").find(expr)
        if (addAndMatch != null) {
            expr = "${addAndMatch.groupValues[1]} + ${addAndMatch.groupValues[3]}"
        }

        // Clean characters: keep only digits, operators, dots, parentheses, whitespace
        val sanitized = expr.filter { it.isDigit() || it in "+-*/^(). " }.trim()
        if (sanitized.isEmpty()) return null

        val result = evaluateExpression(sanitized) ?: return null
        return "${formatNumber(result)}, sir."
    }

    /**
     * Safe recursive-descent expression evaluator (no eval or script engine).
     * Supports +, -, *, /, ^, parentheses, negative numbers, decimals.
     */
    private fun evaluateExpression(expr: String): Double? {
        val tokens = tokenize(expr) ?: return null
        if (tokens.isEmpty()) return null
        return ExpressionParser(tokens).parse()
    }

    private class ExpressionParser(private val tokens: List<String>) {
        private var index = 0

        fun parse(): Double? {
            val res = parseExpression()
            return if (index == tokens.size) res else null
        }

        private fun peek(): String? = tokens.getOrNull(index)
        private fun consume(): String = tokens[index++]

        private fun parseFactor(): Double? {
            val token = peek() ?: return null
            return when {
                token == "+" -> {
                    consume()
                    parseFactor()
                }
                token == "-" -> {
                    consume()
                    val f = parseFactor() ?: return null
                    -f
                }
                token == "(" -> {
                    consume() // (
                    val inner = parseExpression() ?: return null
                    if (peek() == ")") consume()
                    inner
                }
                token.toDoubleOrNull() != null -> {
                    consume().toDouble()
                }
                else -> null
            }
        }

        private fun parseExponent(): Double? {
            var left = parseFactor() ?: return null
            while (peek() == "^") {
                consume()
                val right = parseFactor() ?: return null
                left = left.pow(right)
            }
            return left
        }

        private fun parseTerm(): Double? {
            var left = parseExponent() ?: return null
            while (peek() == "*" || peek() == "/") {
                val op = consume()
                val right = parseExponent() ?: return null
                left = if (op == "*") {
                    left * right
                } else {
                    if (right == 0.0) return null // divide by zero
                    left / right
                }
            }
            return left
        }

        private fun parseExpression(): Double? {
            var left = parseTerm() ?: return null
            while (peek() == "+" || peek() == "-") {
                val op = consume()
                val right = parseTerm() ?: return null
                left = if (op == "+") left + right else left - right
            }
            return left
        }
    }

    private fun tokenize(expr: String): List<String>? {
        val list = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c in "+-*/^()" -> {
                    list.add(c.toString())
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        sb.append(expr[i])
                        i++
                    }
                    list.add(sb.toString())
                }
                else -> return null // invalid token
            }
        }
        return list
    }

    private fun findGcd(a: Long, b: Long): Long {
        var x = kotlin.math.abs(a)
        var y = kotlin.math.abs(b)
        while (y != 0L) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }
}
