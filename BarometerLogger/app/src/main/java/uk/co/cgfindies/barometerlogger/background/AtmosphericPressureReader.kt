package uk.co.cgfindies.barometerlogger.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uk.co.cgfindies.barometerlogger.AppDatabase
import uk.co.cgfindies.barometerlogger.BarometerLoggerApplication
import uk.co.cgfindies.barometerlogger.R
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureReading
import kotlin.math.roundToInt

class AtmosphericPressureReader:
    LifecycleService(),
    SensorEventListener {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "uk.co.cgfindies.barometerlogger.notification_channel_read_atmospheric_pressure_in_background"
        private const val NOTIFICATION_ID = 1000
    }

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val nextSensorReading = Channel<Int>(1)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(BarometerLoggerApplication.APP_TAG, "Reader service started")
        handleAndroidBS()

        Log.d(BarometerLoggerApplication.APP_TAG, "Creating a dispatcher to do the actual work")
        lifecycleScope.launch(Dispatchers.Default) { doWork() }

        Log.d(BarometerLoggerApplication.APP_TAG, "Returning start-not-sticky instruction")
        return START_NOT_STICKY
    }

    private suspend fun doWork() {
        try {
            Log.d(BarometerLoggerApplication.APP_TAG, "Doing the work")
            if (!setupSensors()) return
            insertNextSensorReading()
        } finally {
            cleanup()
        }
    }

    private fun setupSensors(): Boolean {
        Log.d(BarometerLoggerApplication.APP_TAG, "Setting up the sensors")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor == null) {
            Log.d(BarometerLoggerApplication.APP_TAG, "No pressure sensor available, not able to do the work")
            return false
        }

        Log.d(BarometerLoggerApplication.APP_TAG, "Registering the pressure sensor")
        sensorManager.registerListener(
            this@AtmosphericPressureReader,
            pressureSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        return true
    }

    private suspend fun insertNextSensorReading() {
        Log.d(BarometerLoggerApplication.APP_TAG, "Waiting to receive a sensor reading from the channel")
        val reading = nextSensorReading.receive()
        val unixTimestamp = System.currentTimeMillis() / 1000

        Log.d(BarometerLoggerApplication.APP_TAG, "Got most recent reading from channel $reading at $unixTimestamp")

        val db = AppDatabase.getDb(applicationContext)

        Log.d(BarometerLoggerApplication.APP_TAG, "Fetching all readings from the past 24 hours")
        val pastReadings = db.atmosphericPressureReadingDao().getAllReadingsInPast24Hours()

        Log.d(BarometerLoggerApplication.APP_TAG, "Calculating the biggest possible 24h air pressure rise and fall")
        val (deltaIncrease, deltaDecrease) = getDeltasFromPast24Hours(pastReadings, reading)

        Log.d(BarometerLoggerApplication.APP_TAG, "Adding reading to the database")
        db.atmosphericPressureReadingDao().insertAll(AtmosphericPressureReading(0, unixTimestamp, reading, deltaIncrease, deltaDecrease))
    }

    private fun getDeltasFromPast24Hours(pastReadings: List<AtmosphericPressureReading>, reading: Int): Pair<Int, Int> {
        var deltaIncrease = 0
        var deltaDecrease = 0
        val highest = pastReadings.maxByOrNull { it.reading }?.reading ?: reading
        val lowest = pastReadings.minByOrNull { it.reading }?.reading ?: reading

        val highestIn24Hours = reading > highest
        val lowestIn24Hours = reading < lowest
        val inBetweenReadings = !highestIn24Hours && !lowestIn24Hours

        if (highestIn24Hours || inBetweenReadings) {
            deltaIncrease = (reading - lowest)
        }

        if (lowestIn24Hours || inBetweenReadings) {
            deltaDecrease = (highest - reading)
        }

        Log.d(BarometerLoggerApplication.APP_TAG, "Got highest $highest, lowest $lowest, deltaIncrease $deltaIncrease, deltaDecrease $deltaDecrease")
        return Pair(deltaIncrease, deltaDecrease)
    }

    private fun tryUnregisterListeners() {
        if (pressureSensor != null) {
            Log.d(BarometerLoggerApplication.APP_TAG, "We have a pressure sensor reference, unregistering it")
            sensorManager.unregisterListener(this, pressureSensor)
            pressureSensor = null
        }
    }

    private fun cleanup() {
        Log.d(BarometerLoggerApplication.APP_TAG, "Doing cleanup")
        tryUnregisterListeners()

        Log.d(BarometerLoggerApplication.APP_TAG, "Stopping foreground service")
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.d(BarometerLoggerApplication.APP_TAG, "Stopping service")
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) { return }

        val millibarsOfPressure = event.values[0].roundToInt()
        Log.d(BarometerLoggerApplication.APP_TAG, "Got reading - $millibarsOfPressure")

        tryUnregisterListeners()

        lifecycleScope.launch(Dispatchers.Default) {
            Log.d(BarometerLoggerApplication.APP_TAG, "Sending reading to channel")
            nextSensorReading.send(millibarsOfPressure)
            nextSensorReading.close()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) {
        // Pressure sensor accuracy changed but we have no real way of dealing with that information
        Log.d(BarometerLoggerApplication.APP_TAG, "Pressure Sensor accuracy changed to $newAccuracy")
    }

    // The documentation wasn't very clear on this, so I tried it to confirm.
    // As of Android 9, a change as made to restrict access to sensor data in the background.
    // Events are no longer received in the background for any sensor using continuous, on-change, or one-shot reporting modes.
    // For those keeping track, that's basically every single sensor
    // (except maybe step and tilt at time of writing?)
    //
    // I have to assume this was done out of some interest in user data privacy,
    // which makes it a little odd that there's no way to just ask for
    // a discrete value and not a continuous access stream.
    // Since that discrete option isn't available,
    // we're lumped in with the plebeian data brokers who are shoveling up user data like it's the latest designer drug
    //
    // What this all means is we have to access the data in foreground service mode,
    // This serves to turn a blink and you'll miss it sensor read into a lumbering farce
    // where we've got to display a persistent notification,
    // wait for the Android OS to chug through the repercussions of
    // getting that added into the notification manager and drawn to the screen if the screen is on,
    // then do all of what we want to do in the foreground services mode which "can potentially put a heavy load on the device"
    //
    // And if all of that wasn't bad enough,
    // it means that I need to add manifest permissions for notifications and probably foreground services,
    // and add a load of cruft to this file (and my strings file)
    // and more cruft with this explanation.
    private fun handleAndroidBS() {
        Log.d(BarometerLoggerApplication.APP_TAG, "Handling android BS")

        Log.d(BarometerLoggerApplication.APP_TAG, "Creating notification channel")
        val context = applicationContext
        val name = applicationContext.getString(R.string.notification_channel_name)
        val descriptionText = applicationContext.getString(R.string.notification_channel_description)

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_NONE
        ).apply { description = descriptionText }

        val notificationManager: NotificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        Log.d(BarometerLoggerApplication.APP_TAG, "Creating notification")
        val title = context.getString(R.string.notification_title)
        val notification: Notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setSmallIcon(R.drawable.foggy_48px)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(BarometerLoggerApplication.APP_TAG, "Sending startForeground() instruction - >= Android Q")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            Log.d(BarometerLoggerApplication.APP_TAG, "Sending startForeground() instruction - <= Android P")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,  2048 )
        }
    }
}
