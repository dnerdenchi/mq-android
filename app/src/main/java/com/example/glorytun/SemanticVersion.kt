package com.example.glorytun

/** Small SemVer comparator for release tags and BuildConfig.VERSION_NAME. */
internal object SemanticVersion {
    private val versionPattern = Regex(
        """(?i)(?:^|[^a-z0-9])v?(\d+)\.(\d+)\.(\d+)(?:-([0-9a-z-]+(?:\.[0-9a-z-]+)*))?(?:\+[0-9a-z-]+(?:\.[0-9a-z-]+)*)?""",
    )

    private data class Parsed(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val prerelease: List<String>?,
    )

    fun extract(value: String): String? = versionPattern.find(value)?.value
        ?.trimStart { !it.isDigit() }
        ?.takeIf { parse(it) != null }

    /** Returns null when either input does not contain a supported version. */
    fun compare(left: String, right: String): Int? {
        val leftVersion = parse(left) ?: return null
        val rightVersion = parse(right) ?: return null
        return compare(leftVersion, rightVersion)
    }

    private fun parse(value: String): Parsed? {
        val match = versionPattern.find(value) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        val prerelease = match.groupValues[4]
            .takeIf(String::isNotEmpty)
            ?.split('.')
        return Parsed(major, minor, patch, prerelease)
    }

    private fun compare(left: Parsed, right: Parsed): Int {
        compareValuesBy(left, right, Parsed::major, Parsed::minor, Parsed::patch)
            .takeIf { it != 0 }
            ?.let { return it }

        val leftPrerelease = left.prerelease
        val rightPrerelease = right.prerelease
        if (leftPrerelease == null && rightPrerelease == null) return 0
        if (leftPrerelease == null) return 1
        if (rightPrerelease == null) return -1

        for (index in 0 until minOf(leftPrerelease.size, rightPrerelease.size)) {
            val order = compareIdentifier(leftPrerelease[index], rightPrerelease[index])
            if (order != 0) return order
        }
        return leftPrerelease.size.compareTo(rightPrerelease.size)
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)
        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            return normalizedLeft.length.compareTo(normalizedRight.length)
                .takeIf { it != 0 }
                ?: normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric != rightNumeric) return if (leftNumeric) -1 else 1
        return left.compareTo(right)
    }
}
