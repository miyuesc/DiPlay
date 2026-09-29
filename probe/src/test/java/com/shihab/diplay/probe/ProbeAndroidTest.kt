package com.shihab.diplay.probe

import android.Manifest
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProbeAndroidTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun deniedBluetoothPermissionDoesNotPreventOtherCapabilitiesBeingReported() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val report = HeadUnitCapabilityProbe.collect(app, 0)
        assertEquals("permission_denied", report.getJSONObject("bluetooth").getString("status"))
        assertEquals("observed", report.getJSONObject("system").getString("status"))
        assertEquals("observed", report.getJSONObject("displays").getString("status"))
        assertFalse(report.getBoolean("authenticationRequired"))
    }

    @Test fun displayReportOmitsNamesAndNeverMarksMainDisplayAsTestCandidate() {
        val report = HeadUnitCapabilityProbe.collect(app, 0)
        val displays = report.getJSONObject("displays").getJSONArray("data")
        for (index in 0 until displays.length()) {
            val item = displays.getJSONObject(index)
            assertFalse(item.has("name"))
            assertFalse(item.has("uniqueId"))
            assertEquals("not_tested", item.getString("windowAccess"))
            if (item.getInt("id") == 0) assertFalse(item.getBoolean("presentationCandidate"))
        }
        assertTrue(app.getSystemService(DisplayManager::class.java).displays.isNotEmpty())
    }

    @Test fun audioFocusDenialEndsTestWithoutClaimingMediaRoutingWorks() {
        val audio = app.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val controller = Robolectric.buildService(MediaKeyProbeService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(app, MediaKeyProbeService::class.java), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(MediaKeyProbeService.active)
            assertTrue(ProbeState.snapshot().any { it.detail == "focus_denied; routing_not_tested" })
        } finally { controller.destroy() }
    }

    @Test fun launcherCanOpenWithoutAnyAuthenticationAssets() {
        val controller = Robolectric.buildActivity(ProbeActivity::class.java).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(controller.get().findViewById<android.view.View>(android.R.id.content))
            assertFalse(app.assets.list("").orEmpty().contains("offline-mfi"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun playbackAutoStopsAndRepeatedStartCannotExtendDeadline() {
        val audio = app.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val tone = File(app.cacheDir, "probe-tone.wav")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(tone.absolutePath), ShadowMediaPlayer.MediaInfo(2000, 0))
        val controller = Robolectric.buildService(MediaKeyProbeService::class.java).create()
        try {
            val service = controller.get()
            val intent = Intent(app, MediaKeyProbeService::class.java)
            service.onStartCommand(intent, 0, 1)
            assertTrue(MediaKeyProbeService.active)
            assertTrue(tone.isFile)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
            assertFalse(shadowOf(service).isStoppedBySelf)
            service.onStartCommand(intent, 0, 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
        assertFalse(MediaKeyProbeService.active)
        assertFalse(tone.exists())
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun systemVolumeAndVehicleKeysAreNeverConvertedToMediaCommands() {
        assertNull(MediaKeyProbeService.mediaCommand(android.view.KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(MediaKeyProbeService.mediaCommand(android.view.KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(MediaKeyProbeService.mediaCommand(android.view.KeyEvent.KEYCODE_CALL))
        assertEquals("pause", MediaKeyProbeService.mediaCommand(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals("toggle", MediaKeyProbeService.mediaCommand(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test fun displayTestRequiresConfirmationAndClosesWhenActivityLeaves() {
        ShadowDisplayManager.addDisplay("w1280dp-h720dp", android.view.Display.FLAG_PRESENTATION)
        val controller = Robolectric.buildActivity(ProbeActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        try {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            fun findButton(view: android.view.View): android.widget.Button? {
                if (view is android.widget.Button && view.text == activity.getString(R.string.display_test)) return view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) findButton(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val before = ProbeState.snapshot().count { "window_accepted" in it.detail }
            checkNotNull(findButton(content)).performClick()
            shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
            assertEquals(before, ProbeState.snapshot().count { "window_accepted" in it.detail })
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before + 1, ProbeState.snapshot().count { "window_accepted" in it.detail })
            assertFalse(ProbeState.snapshot().any { it.detail.contains("owner_reported_visible") })
            controller.pause()
            assertTrue(ProbeState.snapshot().any { it.detail.contains("closed=activity_paused") })
        } finally { controller.stop().destroy() }
    }
}
