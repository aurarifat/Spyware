package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.SafeLogger
import com.example.core.security.DeviceIdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Family Wellbeing", appName)
    }

    @Test
    fun `deviceIdManager generates privacy safe device id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DeviceIdManager(context)
        val id1 = manager.getOrCreateDeviceId()
        val id2 = manager.getOrCreateDeviceId()

        assertNotNull(id1)
        assertTrue(id1.startsWith("dev_"))
        assertEquals(id1, id2) // Persistence check
    }

    @Test
    fun `safeLogger redacts phone numbers`() {
        val raw = "Calling +18005551234 on device"
        val sanitized = SafeLogger.sanitize(raw)
        assertTrue(sanitized.contains("[REDACTED_PHONE]"))
        assertTrue(!sanitized.contains("5551234"))
    }
}
