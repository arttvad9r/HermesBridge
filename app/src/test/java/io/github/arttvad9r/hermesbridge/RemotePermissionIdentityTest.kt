package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePermissionIdentityTest {
    @Test
    fun validPermissionIdentityIsNotNormalizedOrTruncated() {
        val exactName = "com.example.permission." + "x".repeat(220)
        val source = AppPermissionSnapshot(
            name = exactName,
            granted = true,
            protection = "dangerous\nprivate-display-text",
            dangerous = true,
            group = "group\nprivate-display-text",
            implicit = false,
            neverForLocation = false,
        )

        val projection = projectPermissionsForRemoteResult(listOf(source))

        assertTrue(isValidAndroidQualifiedName(exactName))
        assertFalse(projection.truncated)
        assertEquals(exactName, projection.permissions.single().name)
        assertFalse(projection.permissions.single().protection.any(Char::isISOControl))
        assertFalse(projection.permissions.single().group.orEmpty().any(Char::isISOControl))
    }

    @Test
    fun malformedPermissionIdentityIsDroppedInsteadOfRewritten() {
        val malformedNames = listOf(
            "android.permission.CAMERA\nforged",
            "android.permission.CAMERA forged",
        )

        malformedNames.forEach { malformedName ->
            val source = AppPermissionSnapshot(
                name = malformedName,
                granted = true,
                protection = "dangerous",
                dangerous = true,
                group = null,
                implicit = false,
                neverForLocation = false,
            )

            val projection = projectPermissionsForRemoteResult(listOf(source))

            assertFalse(isValidAndroidQualifiedName(malformedName))
            assertEquals(1, projection.totalPermissionCount)
            assertTrue(projection.permissions.isEmpty())
            assertTrue(projection.truncated)
        }
    }
}
