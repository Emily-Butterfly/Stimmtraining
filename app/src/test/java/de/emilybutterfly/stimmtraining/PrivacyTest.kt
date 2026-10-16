package de.emilybutterfly.stimmtraining

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Aufnahmen und Analysen bleiben auf dem Handy: Die App darf nicht ins Internet. Schlägt der
 * Test fehl, hat z. B. eine neue Bibliothek die Berechtigung über ihr Manifest eingeschleppt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyTest {
    @Test
    fun appCannotAccessTheInternet() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toList()
        assertFalse(permissions.toString(), Manifest.permission.INTERNET in permissions)
    }
}
