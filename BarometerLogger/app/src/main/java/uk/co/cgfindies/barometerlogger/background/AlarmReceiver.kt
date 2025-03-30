package uk.co.cgfindies.barometerlogger.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import uk.co.cgfindies.barometerlogger.BarometerLoggerApplication

class AlarmReceiver: BroadcastReceiver() {
    companion object {
        private const val MILLISECONDS_IN_A_MINUTE = 60000L
        private const val BROADCAST_REQUEST_ID_TAKE_ATMOSPHERIC_PRESSURE_READING = 1000

        fun setFirstAlarmIfNoneExists(context: Context) {
            Log.d(BarometerLoggerApplication.APP_TAG, "Will set an alarm if none exists yet")
            if (getAlarmPendingIntent(context, true) == null) {
                Log.d(BarometerLoggerApplication.APP_TAG, "No alarm found, setting one now that should trigger in 2 minutes")
                setNextAlarm(context, 2)
            }
        }

        private fun getAlarmPendingIntent(context: Context, noCreateMode: Boolean = false): PendingIntent? {
            val mode = if (noCreateMode) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT

            return PendingIntent.getBroadcast(
                context,
                BROADCAST_REQUEST_ID_TAKE_ATMOSPHERIC_PRESSURE_READING,
                Intent(context,AlarmReceiver::class.java),
                mode
            )
        }

        private fun setNextAlarm(context: Context) {
            setNextAlarm(context, 15)
        }

        private fun setNextAlarm(context: Context, delayInMinutes: Int) {
            val triggerTimeMs = System.currentTimeMillis() + (delayInMinutes * MILLISECONDS_IN_A_MINUTE)

            val trigger = getAlarmPendingIntent(context)
            if (trigger == null) {
                Log.d(BarometerLoggerApplication.APP_TAG, "Couldn't get the pending intent for the reader service so can't set the alarm")
                return
            }

            Log.d(BarometerLoggerApplication.APP_TAG, "Setting alarm to trigger inexactly but while idle for unix timestamp $triggerTimeMs")
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, trigger)
            } catch (e: Exception) {
                Log.e(BarometerLoggerApplication.APP_TAG, "Could not set alarm", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(BarometerLoggerApplication.APP_TAG, "Alarm broadcast received")

        Log.d(BarometerLoggerApplication.APP_TAG, "Rescheduling the alarm")
        setNextAlarm(context)

        Log.d(BarometerLoggerApplication.APP_TAG, "Starting reader foreground service")
        context.startForegroundService(
            Intent(context, AtmosphericPressureReader::class.java)
        )
    }
}

class SetAlarmOnBootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(BarometerLoggerApplication.APP_TAG, "Received boot broadcast")
        if (intent.action == "android.intent.action.BOOT_COMPLETED") {
            Log.d(BarometerLoggerApplication.APP_TAG, "Boot completed, setting alarm for 2 minutes")
            AlarmReceiver.setFirstAlarmIfNoneExists(context)
        }

    }
}