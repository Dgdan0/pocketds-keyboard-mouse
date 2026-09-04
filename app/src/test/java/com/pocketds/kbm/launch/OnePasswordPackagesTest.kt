package com.pocketds.kbm.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which 1Password package to launch.
 *
 * There is more than one: version 8 ships as `com.onepassword.android`, and
 * version 7 as `com.agilebits.onepassword`. The old code hardcoded one of them
 * and gave up silently when it was not found, so a v7 user got a button that
 * did nothing and said nothing.
 */
class OnePasswordPackagesTest {

    private fun resolveAmong(vararg installed: String): String? =
        OnePasswordPackages.resolve(installed.toSet()::contains)

    @Test
    fun `finds version 8`() {
        assertEquals("com.onepassword.android", resolveAmong("com.onepassword.android"))
    }

    @Test
    fun `finds version 7`() {
        assertEquals("com.agilebits.onepassword", resolveAmong("com.agilebits.onepassword"))
    }

    @Test
    fun `prefers version 8 when both are installed`() {
        val resolved = resolveAmong("com.agilebits.onepassword", "com.onepassword.android")

        assertEquals("com.onepassword.android", resolved)
    }

    @Test
    fun `returns nothing when 1Password is absent`() {
        // Nothing to launch has to be distinguishable, so the caller can say so
        // rather than failing quietly the way the old code did.
        assertNull(resolveAmong())
        assertNull(resolveAmong("com.example.notapasswordmanager"))
    }

    @Test
    fun `every candidate is checked`() {
        val asked = mutableListOf<String>()
        OnePasswordPackages.resolve { candidate ->
            asked += candidate
            false
        }

        assertEquals(OnePasswordPackages.candidates, asked)
    }
}
