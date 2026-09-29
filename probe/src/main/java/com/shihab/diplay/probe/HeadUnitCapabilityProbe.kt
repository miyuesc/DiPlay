package com.shihab.diplay.probe

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.NetworkInterface

internal object HeadUnitCapabilityProbe {
    fun collect(context: Context, ownDisplayId: Int): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("probeVersion", "0.1.0")
        put("target", "C11 2024 BEV 580 Premium / 3.11.40 (owner supplied)")
        put("phone", "iPhone 13 / iOS 27.0 (owner supplied, not detected)")
        put("authenticationRequired", false)
        put("system", section {
            JSONObject().apply {
                put("sdk", Build.VERSION.SDK_INT)
                put("release", ProbeText.safe(Build.VERSION.RELEASE))
                put("securityPatch", ProbeText.safe(Build.VERSION.SECURITY_PATCH))
                put("manufacturer", ProbeText.safe(Build.MANUFACTURER))
                put("model", ProbeText.safe(Build.MODEL))
                put("fingerprint", ProbeText.safe(Build.FINGERPRINT))
                put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("features", JSONObject().apply {
                    for (feature in listOf(PackageManager.FEATURE_AUTOMOTIVE,
                        PackageManager.FEATURE_WIFI, PackageManager.FEATURE_WIFI_DIRECT,
                        PackageManager.FEATURE_BLUETOOTH, PackageManager.FEATURE_USB_HOST)) {
                        put(feature, context.packageManager.hasSystemFeature(feature))
                    }
                })
            }
        })
        put("permissions", JSONObject().apply {
            put("bluetoothConnect", if (Build.VERSION.SDK_INT < 31) "not_required" else permission(context, Manifest.permission.BLUETOOTH_CONNECT))
            put("notifications", if (Build.VERSION.SDK_INT < 33) "not_required" else permission(context, Manifest.permission.POST_NOTIFICATIONS))
        })
        put("bluetooth", section {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException()
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            JSONObject().apply {
                put("present", adapter != null)
                if (adapter != null) {
                    put("enabled", adapter.isEnabled)
                    put("pairedDeviceCount", adapter.bondedDevices.size)
                }
                put("rfcommConnection", "not_tested")
            }
        })
        put("wifi", section {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            JSONObject().apply {
                put("enabled", wifi?.isWifiEnabled)
                put("supports5GhzStation", wifi?.is5GHzBandSupported)
                put("hotspotControl", "not_attempted")
                put("hotspotClientReachability", "not_tested")
                put("p2pGroupCreation", "not_tested")
                put("note", "Station 5GHz support does not establish hotspot/P2P support")
            }
        })
        put("network", section {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            JSONObject().apply {
                put("defaultWifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false)
                put("defaultCellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false)
                put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
                put("interfaces", JSONArray().apply {
                    NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEachIndexed { index, iface ->
                        val addresses = iface.inetAddresses.toList()
                        put(JSONObject().apply {
                            put("ordinal", index)
                            put("ipv4Count", addresses.count { it is java.net.Inet4Address })
                            put("ipv6Count", addresses.count { it is Inet6Address })
                            put("scopedLinkLocalV6Count", addresses.count { it is Inet6Address && it.isLinkLocalAddress && it.scopeId != 0 })
                        })
                    }
                })
                put("note", "No interface names/addresses exported; interface presence does not identify the hotspot")
            }
        })
        put("displays", section {
            JSONArray().apply {
                context.getSystemService(DisplayManager::class.java)?.displays?.forEach { display ->
                    put(JSONObject().apply {
                        val mode = display.mode
                        put("id", display.displayId)
                        put("ownDisplay", display.displayId == ownDisplayId)
                        put("valid", display.isValid)
                        put("width", mode.physicalWidth)
                        put("height", mode.physicalHeight)
                        put("refreshRate", mode.refreshRate.toDouble())
                        put("flags", display.flags)
                        put("presentationCandidate", eligible(display, ownDisplayId))
                        put("role", "unverified")
                        put("windowAccess", "not_tested")
                    })
                }
            }
        })
        put("h264Decoders", section {
            JSONArray().apply {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                    !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
                }.forEach { codec ->
                    put(section {
                        val caps = checkNotNull(codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities)
                        JSONObject().apply {
                            put("name", ProbeText.safe(codec.name))
                            put("declares720p30", caps.areSizeAndRateSupported(1280, 720, 30.0))
                            put("declares1080p30", caps.areSizeAndRateSupported(1920, 1080, 30.0))
                            put("actualDecode", "not_tested")
                        }
                    })
                }
            }
        })
        put("limitations", JSONArray(listOf("No connection to iPhone or authentication test",
            "No actual video decoding; codec declarations only",
            "Display visibility is not proof of cluster access",
            "Media key observations require the timed playback test",
            "No shell, root, vendor IPC or vehicle control used")))
    }

    fun eligible(display: Display, ownId: Int): Boolean = DisplayProbePolicy.eligible(
        display.displayId, ownId, display.isValid,
        display.flags and Display.FLAG_PRESENTATION != 0,
        display.flags and Display.FLAG_PRIVATE != 0,
    )

    private fun permission(context: Context, name: String): String =
        if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"

    private fun section(block: () -> Any): JSONObject = try {
        JSONObject().put("status", "observed").put("data", block())
    } catch (_: SecurityException) {
        JSONObject().put("status", "permission_denied")
    } catch (failure: Exception) {
        // Exception messages can contain identifiers or permission payloads.
        JSONObject().put("status", "unavailable").put("errorType", failure.javaClass.simpleName)
    }
}
