package dev.maksim.companion.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    private fun assertNewer(version: String, than: String) {
        assertTrue("$version should be newer than $than", GitHubReleases.isNewer(version, than))
        assertFalse("$than shouldn't be newer than $version", GitHubReleases.isNewer(than, version))
    }

    @Test
    fun releases() {
        assertNewer("2.2.4", "2.2.3")
        assertNewer("3.0.0", "2.9.9")
        assertNewer("2.10.0", "2.9.0")
        assertFalse(GitHubReleases.isNewer("2.2.4", "2.2.4"))
    }

    @Test
    fun betasComeBetweenReleases() {
        assertNewer("3.1.0-beta.1", "3.0.0")
        assertNewer("3.1.0", "3.1.0-beta.1")
        assertNewer("3.1.0-beta.2", "3.1.0-beta.1")
        assertNewer("3.1.0-beta.10", "3.1.0-beta.9")
        assertNewer("3.0.1", "3.0.1-beta.5")
        assertFalse(GitHubReleases.isNewer("3.1.0-beta.1", "3.1.0-beta.1"))
    }

    @Test
    fun otherSuffixesAreIgnored() {
        assertFalse(GitHubReleases.isNewer("2.2.4", "2.2.4-dev"))
        assertFalse(GitHubReleases.isNewer("2.2.4-dev", "2.2.4"))
    }
}
