package com.mantra.sampleplayer

import org.json.JSONObject

/**
 * IS THERE A NEWER ONE, AND WHERE IS IT.
 *
 * The app is installed by downloading an APK from a GitHub release, which means there is no store
 * to notice a new version and nothing to notice it on Baba's behalf. Until now the only way to
 * find out was to remember to look, and the versions that matter most are the ones that fix
 * something he has just hit.
 *
 * IT REPORTS AND HANDS OVER. It does not download and it does not install: Android will not let an
 * app replace itself without the package installer, and an app that asked for that permission in
 * order to save one tap would be asking for the ability to install anything at all. So it finds
 * the release, says what it found, and opens the browser at the APK.
 *
 * ANONYMOUS. The releases endpoint is public and the repository is public, so no token is sent —
 * an app that carried one to check its own version would be an app carrying a token.
 */
object Updates {

    private const val LATEST =
        "https://api.github.com/repos/markoboskoauroville/SAMPLE_PLAYER/releases/latest"

    data class Result(
        val installed: String,
        val latest: String?,
        val url: String?,
        val behind: Boolean,
        val why: String,
    )

    /**
     * Compare [installed] against the newest release.
     *
     * NUMBERS, NOT STRINGS. "v9" is greater than "v10" alphabetically, and the day that mattered
     * would have been the day the app stopped offering updates and nobody could see why.
     */
    fun compare(installed: String, latest: String?): Boolean {
        if (latest == null) return false
        val a = Regex("\\d+").findAll(installed).map { it.value.toInt() }.toList()
        val b = Regex("\\d+").findAll(latest).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    fun check(installed: String): Result {
        val r = Net.get(LATEST, mapOf("Accept" to "application/vnd.github+json"))
        if (!r.ok) {
            return Result(installed, null, null, false, "could not reach GitHub: ${r.code}")
        }
        val root = runCatching { JSONObject(r.body) }.getOrNull()
            ?: return Result(installed, null, null, false, "GitHub sent something unreadable")
        val tag = root.optString("tag_name").takeIf { it.isNotBlank() }
        // The APK, not the source zip. A release carries both and the wrong one is a download that
        // does nothing when it is tapped.
        var url: String? = null
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    url = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        return Result(
            installed = installed,
            latest = tag,
            url = url,
            behind = compare(installed, tag),
            why = if (tag == null) "that release has no tag" else "",
        )
    }
}
