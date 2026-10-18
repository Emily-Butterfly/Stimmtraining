package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun gentleModeIsOffByDefaultAndRemembered() {
        val settings = AppSettings(context)
        assertEquals(false, settings.gentleMode.value)
        settings.setGentleMode(true)
        assertEquals(true, settings.gentleMode.value)
        assertEquals("nach einem Neustart der App", true, AppSettings(context).gentleMode.value)
    }
}
