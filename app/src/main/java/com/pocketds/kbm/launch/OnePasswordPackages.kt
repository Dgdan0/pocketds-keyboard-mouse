package com.pocketds.kbm.launch

/**
 * Which 1Password to open.
 *
 * There is more than one package name in the wild — version 8 and version 7 —
 * and the old code hardcoded a single one in two separate places, giving up
 * silently when it was not found. Kept free of Android types so the choice is
 * testable; the caller supplies the "is this installed?" check.
 */
object OnePasswordPackages {

    /** Newest first: if someone has both, open the current one. */
    val candidates = listOf(
        "com.onepassword.android",   // 1Password 8
        "com.agilebits.onepassword"  // 1Password 7
    )

    /**
     * The package to launch, or null if none is installed — which the caller is
     * expected to report rather than swallow.
     *
     * Note that on Android 11+ an app can only see packages it declares in
     * `<queries>`, so [isInstalled] answers "installed *and* visible to us".
     * Missing that declaration is what made this fail invisibly: the launch
     * intent came back null even with 1Password installed.
     */
    fun resolve(isInstalled: (String) -> Boolean): String? =
        candidates.firstOrNull(isInstalled)
}
