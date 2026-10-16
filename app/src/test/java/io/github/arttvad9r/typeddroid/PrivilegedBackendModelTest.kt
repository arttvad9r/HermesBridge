package io.github.arttvad9r.typeddroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedBackendModelTest {
    @Test
    fun embeddedAdbRepresentsShellLevelAuthorityWithoutRoot() {
        assertTrue(PrivilegedBackendProfiles.EmbeddedAdb.rawShell)
        assertTrue(PrivilegedBackendProfiles.EmbeddedAdb.packageManager)
        assertTrue(PrivilegedBackendProfiles.EmbeddedAdb.activityManager)
        assertFalse(PrivilegedBackendProfiles.EmbeddedAdb.root)
    }

}
