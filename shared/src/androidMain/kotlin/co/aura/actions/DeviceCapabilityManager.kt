package co.aura.actions

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages native Android hardware, sensors, connectivity, battery, volume, and storage inspection.
 * Fulfills device status queries locally with ZERO Gemini token usage.
 */
class DeviceCapabilityManager(
    private val context: Context
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var isTorchOn: Boolean = false

    init {
        try {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    isTorchOn = enabled
                }
            }, null)
        } catch (e: Exception) {
            // Torch callback optional
        }
    }

    fun getCurrentTime(): ActionResult {
        val formattedTime = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        return ActionResult.Success("It is $formattedTime, sir.")
    }

    fun getCurrentDate(): ActionResult {
        val formattedDate = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
        return ActionResult.Success("Today is $formattedDate, sir.")
    }

    fun getAndroidVersion(): ActionResult {
        val release = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        val model = Build.MODEL
        return ActionResult.Success("You are running Android $release (API $sdk) on $model, sir.")
    }

    fun getBatteryLevel(): ActionResult {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return if (batteryPct >= 0) {
            val chargeNote = if (isCharging) " and currently charging" else ""
            ActionResult.Success("Your battery is at $batteryPct percent$chargeNote, sir.")
        } else {
            ActionResult.Failure("Sir, I was unable to read the battery level.")
        }
    }

    fun getChargingStatus(): ActionResult {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_AC -> "via AC power"
            BatteryManager.BATTERY_PLUGGED_USB -> "via USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wirelessly"
            else -> ""
        }

        return if (isCharging) {
            ActionResult.Success("Your device is currently charging ${plugType.trim()}, sir.")
        } else {
            ActionResult.Success("Your device is not currently charging, sir.")
        }
    }

    fun getStorageInfo(): ActionResult {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize

            val df = DecimalFormat("#0.0", DecimalFormatSymbols(Locale.US))
            val totalGb = df.format(totalBytes.toDouble() / (1024 * 1024 * 1024))
            val freeGb = df.format(availableBytes.toDouble() / (1024 * 1024 * 1024))
            val freePct = ((availableBytes.toDouble() / totalBytes) * 100).toInt()

            ActionResult.Success("You have $freeGb GB available out of $totalGb GB total storage ($freePct% free), sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to read storage statistics", e)
            ActionResult.Failure("Sir, I was unable to read the available storage.")
        }
    }

    fun getConnectivityStatus(): ActionResult {
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)

        return when {
            caps == null -> ActionResult.Success("You are currently offline, sir. No active network connection detected.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                ActionResult.Success("You are connected to the internet via Wi-Fi, sir.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                ActionResult.Success("You are connected to the internet via mobile cellular data, sir.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                ActionResult.Success("You are connected to the internet via Ethernet, sir.")
            else -> ActionResult.Success("Internet connection is active, sir.")
        }
    }

    fun getWifiStatus(): ActionResult {
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)
        val isWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return if (isWifi) {
            ActionResult.Success("Wi-Fi is currently connected, sir.")
        } else {
            ActionResult.Success("Wi-Fi is not currently connected, sir.")
        }
    }

    fun getBluetoothStatus(): ActionResult {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                ActionResult.Failure("Sir, Bluetooth is not supported on this hardware.")
            } else if (adapter.isEnabled) {
                ActionResult.Success("Bluetooth is currently enabled, sir.")
            } else {
                ActionResult.Success("Bluetooth is currently disabled, sir.")
            }
        } catch (e: Exception) {
            AuraLogger.w(LogCategory.ACTION, "Could not determine bluetooth status: ${e.message}")
            ActionResult.Failure("Sir, I couldn't read the Bluetooth status.")
        }
    }

    fun getFlashlightStatus(): ActionResult {
        return if (isTorchOn) {
            ActionResult.Success("The flashlight is currently on, sir.")
        } else {
            ActionResult.Success("The flashlight is currently off, sir.")
        }
    }

    fun setFlashlight(enabled: Boolean): ActionResult {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ActionResult.Failure("Sir, this device does not possess a flashlight.")
            cameraManager.setTorchMode(cameraId, enabled)
            isTorchOn = enabled
            if (enabled) ActionResult.Success("Flashlight on, sir.")
            else ActionResult.Success("Flashlight off, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to toggle flashlight", e)
            ActionResult.Failure("Sir, I couldn't toggle the flashlight.")
        }
    }

    fun adjustVolumeStep(increase: Boolean): ActionResult {
        return try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            if (increase) ActionResult.Success("Volume increased, sir.")
            else ActionResult.Success("Volume decreased, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to adjust volume", e)
            ActionResult.Failure("Sir, I was unable to adjust the volume.")
        }
    }

    fun setVolumeLevel(level: Int): ActionResult {
        val clamped = level.coerceIn(0, 100)
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (clamped * maxVolume / 100.0).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            ActionResult.Success("Volume set to $clamped percent, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to set volume", e)
            ActionResult.Failure("Sir, I was unable to set the volume level.")
        }
    }

    fun muteVolume(): ActionResult {
        return try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            ActionResult.Success("Audio muted, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to mute volume", e)
            ActionResult.Failure("Sir, I was unable to mute the volume.")
        }
    }

    fun showVolumePanel(): ActionResult {
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            ActionResult.Success("Volume controls opened, sir.")
        } catch (e: Exception) {
            ActionResult.Failure("Sir, I couldn't open the volume panel.")
        }
    }
}
