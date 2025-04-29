package uk.co.cgfindies.barometerlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.co.cgfindies.barometerlogger.background.AlarmReceiver
import uk.co.cgfindies.barometerlogger.dao.AtmosphericPressureReadingDao
import uk.co.cgfindies.barometerlogger.databinding.ActivityMainBinding
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureMappingSessionReading
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {
    companion object {
        private const val REQUEST_PERMISSION_LOCATION = 1000
        private const val CSV_PREVIOUS_READINGS_HEADERS = "Date & Time,Reading,Delta Increase,Delta Decrease\n"
        private const val CSV_NO_HEADERS = ""
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AtmosphericPressureReadingDao

    private lateinit var savePreviousReadingsSummariesLauncher: ActivityResultLauncher<String>
    private lateinit var savePreviousReadingsLauncher: ActivityResultLauncher<String>
    private var previousReadingsFromUnixTimestamp = 0L
    private var previousReadingsToUnixTimestamp = 0L
    private var csvHasHeaderRow = false

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var pressureSensor: Sensor? = null
    private var mappedReadings: MutableList<AtmosphericPressureMappingSessionReading> = mutableListOf()
    private var lastAtmosphericPressureReading = 0
    private var sessionId = ""
    private var mappingInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow
            ), drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        savePreviousReadingsSummariesLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { actualFileUri ->
            savePreviousReadingsSummaries(actualFileUri)
        }

        savePreviousReadingsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { actualFileUri ->
            savePreviousReadings(actualFileUri)
        }

        findViewById<Button>(R.id.copy_previous_readings_btn).setOnClickListener{
            csvHasHeaderRow = findViewById<CheckBox>(R.id.include_headers_in_csv).isChecked
            val clipboard = getSystemService(this, ClipboardManager::class.java)

            if (clipboard == null) {
                showToast(R.string.cannot_copy_to_clipboard)
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Barometer Readings ", getPreviousReadingsAsCsv())
                    )
                lifecycleScope.launch(Dispatchers.Main) {
                    showToast(R.string.copied_to_clipboard)
                }
            }
        }

        findViewById<Button>(R.id.export_previous_readings_btn).setOnClickListener{
            savePreviousReadingsLauncher.launch(
                "previous-barometer-readings-exported-${formatDateTime(System.currentTimeMillis()/1000)}.csv"
            )
        }

        findViewById<AppCompatButton>(R.id.export_previous_readings_from_btn).setOnClickListener {
            val picker = DatePickerDialog(this@MainActivity)
            picker.setOnDateSetListener { _, year, monthOfYear, dayOfMonth ->
                previousReadingsFromUnixTimestamp = parseDateTime("$year-${monthOfYear+1}-$dayOfMonth 00:00:00")
            }
            picker.show()
        }

        findViewById<AppCompatButton>(R.id.export_previous_readings_to_btn).setOnClickListener {
            val picker = DatePickerDialog(this@MainActivity)
            picker.setOnDateSetListener { _, year, monthOfYear, dayOfMonth ->
                previousReadingsToUnixTimestamp = parseDateTime("$year-${monthOfYear+1}-$dayOfMonth 23:59:59")
            }
            picker.show()
        }

        findViewById<Button>(R.id.start_mapping_session).setOnClickListener{
            startMappingSession()
        }

        findViewById<Button>(R.id.show_daily_summaries).setOnClickListener{
            refreshPreviousReadings()
        }

        findViewById<Button>(R.id.show_sessions).setOnClickListener{
            refreshSessions()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            permissions.forEachIndexed { index, permission ->
                val isTargetPermission =
                    permission == Manifest.permission.ACCESS_FINE_LOCATION || permission == Manifest.permission.ACCESS_COARSE_LOCATION
                if (isTargetPermission && grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                    startMappingSession()
                    return@onRequestPermissionsResult
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        db = AppDatabase.getDb(applicationContext).atmosphericPressureReadingDao()
        refreshPreviousReadings()
        Log.d(BarometerLoggerApplication.APP_TAG, "MainActivity onResume, running setFirstAlarmIfNoneExists()")
        AlarmReceiver.setFirstAlarmIfNoneExists(this)
    }

    override fun onPause() {
        super.onPause()
        endMappingSession()
    }

    private fun showToast(@StringRes resourceId: Int) {
        Toast.makeText(applicationContext, resourceId, Toast.LENGTH_LONG).show()
    }

    private fun showToast(@StringRes resourceId: Int, vararg formatArgs: Any) {
        Toast.makeText(applicationContext, getString(resourceId, formatArgs), Toast.LENGTH_LONG).show()
    }

    private fun clearViews() {
        findViewById<TableLayout>(R.id.previous_readings).removeAllViews()
        findViewById<TableLayout>(R.id.sessions).removeAllViews()
        findViewById<TableLayout>(R.id.session).removeAllViews()
        refreshLatestReading()
    }

    private fun savePreviousReadingsSummaries(actualFileUri: Uri?) {
        if (actualFileUri == null) {
            // Assume intent was cancelled
            showToast(R.string.save_cancelled)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val csv = "Date,Min Reading,Max Reading,Max Delta Increase,Max Delta Decrease\n" +
                db.getAllGroupedByDay().joinToString(separator = "\n") {
                    "\"${it.summaryDate}\",${it.minReading},${it.maxReading},${it.maxDeltaIncrease},${it.maxDeltaDecrease}"
                }

            val fileWritten = writeToFile(actualFileUri, csv)

            lifecycleScope.launch(Dispatchers.Main) {
                if (fileWritten) {
                    showToast(R.string.file_saved)
                } else {
                    showToast(R.string.error_saving_file)
                }
            }
        }
    }

    private fun savePreviousReadings(actualFileUri: Uri?) {
        if (actualFileUri == null) {
            // Assume intent was cancelled
            showToast(R.string.save_cancelled)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {


            val fileWritten = writeToFile(actualFileUri, getPreviousReadingsAsCsv())

            lifecycleScope.launch(Dispatchers.Main) {
                if (fileWritten) {
                    showToast(R.string.file_saved)
                } else {
                    showToast(R.string.error_saving_file)
                }
            }
        }
    }

    private fun writeToFile(actualFileUri: Uri, csv: String): Boolean {
        val outputStream = contentResolver.openOutputStream(actualFileUri)
        try {
            val charset = Charsets.UTF_8
            outputStream?.write(csv.toByteArray(charset))
            return true
        } catch (e: IOException) {
            Log.d("Debug", " IOException = " + e.message)
            return false
        } finally {
            outputStream?.close()
        }
    }

    private fun refreshLatestReading(){
        lifecycleScope.launch(Dispatchers.IO) {
            val latest = db.getLatestReading() ?: return@launch
            val latestReadingFormatted = getString(R.string.latest_reading, latest.reading, relativeTime(latest.unixTimestamp))

            lifecycleScope.launch(Dispatchers.Main) {
                latestReadingFormatted.also { findViewById<TextView>(R.id.latest_reading).text = it }
            }
        }
    }

    private fun refreshPreviousReadings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val readings = db.getAllGroupedByDay()

            lifecycleScope.launch(Dispatchers.Main) {
                clearViews()

                val readingsTableLayout = findViewById<TableLayout>(R.id.previous_readings)

                val saveReadings = AppCompatButton(this@MainActivity)
                saveReadings.setOnClickListener { _ -> savePreviousReadingsSummariesLauncher.launch("daily-barometer-readings-exported-${formatDate(System.currentTimeMillis()/1000)}.csv") }
                saveReadings.contentDescription = getString(R.string.save_previous_readings)
                saveReadings.setCompoundDrawablesWithIntrinsicBounds(AppCompatResources.getDrawable(this@MainActivity, R.drawable.baseline_save_24), null, null, null)
                saveReadings.minWidth = 48
                saveReadings.minHeight = 48
                saveReadings.setPaddingRelative(50, 0, 0, 0)

                val saveReadingsParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
                saveReadingsParams.setMargins(5,0,5,0)
                saveReadings.layoutParams = saveReadingsParams
                val saveIconTableRow = TableRow(this@MainActivity)
                saveIconTableRow.addView(saveReadings)
                readingsTableLayout.addView(saveIconTableRow)

                readings.map { it ->
                    val tableRow = TableRow(applicationContext)

                    val dateCol = TextView(this@MainActivity)
                    it.summaryDate.also { dateCol.text = it }
                    tableRow.addView(dateCol)

                    val minReadingCol = TextView(this@MainActivity)
                    minReadingCol.setPaddingRelative(25, 0, 25, 0)
                    minReadingCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    it.minReading.toString().also { minReadingCol.text = it }
                    tableRow.addView(minReadingCol)

                    val maxReadingCol = TextView(this@MainActivity)
                    maxReadingCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    it.maxReading.toString().also { maxReadingCol.text = it }
                    tableRow.addView(maxReadingCol)

                    val maxDeltaIncreaseCol = TextView(this@MainActivity)
                    maxDeltaIncreaseCol.setPaddingRelative(25, 0, 25, 0)
                    maxDeltaIncreaseCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    it.maxDeltaIncrease.toString().also { maxDeltaIncreaseCol.text = it }
                    tableRow.addView(maxDeltaIncreaseCol)

                    val maxDeltaDecreaseCol = TextView(this@MainActivity)
                    maxDeltaDecreaseCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    it.maxDeltaDecrease.toString().also { maxDeltaDecreaseCol.text = it }
                    tableRow.addView(maxDeltaDecreaseCol)
                    readingsTableLayout.addView(tableRow)
                }
            }
        }
    }

    private fun refreshSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val mapped = db.getAllMappedSummary()

            lifecycleScope.launch(Dispatchers.Main) {
                clearViews()

                val sessionsTableLayout = findViewById<TableLayout>(R.id.sessions)

                mapped.map { it ->
                    val tableRow = TableRow(applicationContext)

                    val startDateCol = TextView(this@MainActivity)
                    formatDateTime(it.startUnixTimestamp).also { startDateCol.text = it }
                    tableRow.addView(startDateCol)

                    val endDateCol = TextView(this@MainActivity)
                    endDateCol.setPaddingRelative(25, 0, 25, 0)
                    formatDateTime(it.endUnixTimestamp).also { endDateCol.text = it }
                    tableRow.addView(endDateCol)

                    val readingsCol = TextView(this@MainActivity)
                    readingsCol.setPaddingRelative(0, 0, 25, 0)
                    it.numberOfReadings.toString().also { readingsCol.text = it }
                    tableRow.addView(readingsCol)

                    val viewSession = AppCompatButton(this@MainActivity)
                    viewSession.setOnClickListener { _ -> showSession(it.sessionId) }
                    viewSession.contentDescription = getString(R.string.see_session_readings)
                    viewSession.setCompoundDrawablesWithIntrinsicBounds(AppCompatResources.getDrawable(this@MainActivity, R.drawable.baseline_list_24), null, null, null)
                    viewSession.minWidth = 48
                    viewSession.minHeight = 48
                    viewSession.setPaddingRelative(50, 0, 0, 0)

                    val viewSessionParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
                    viewSessionParams.setMargins(5,0,5,0)
                    viewSession.layoutParams = viewSessionParams
                    tableRow.addView(viewSession)

                    val deleteSession = AppCompatButton(this@MainActivity)
                    deleteSession.setOnClickListener { _ -> deleteSessionReadings(it.sessionId) }
                    deleteSession.contentDescription = getString(R.string.delete_session_readings)
                    deleteSession.setCompoundDrawablesWithIntrinsicBounds(AppCompatResources.getDrawable(this@MainActivity, R.drawable.baseline_delete_24), null, null, null)
                    deleteSession.minWidth = 48
                    deleteSession.minHeight = 48
                    deleteSession.setPaddingRelative(50, 0, 0, 0)

                    val deleteSessionParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
                    deleteSessionParams.setMargins(5,0,5,0)
                    deleteSession.layoutParams = deleteSessionParams
                    tableRow.addView(deleteSession)

                    sessionsTableLayout.addView(tableRow)
                }
            }
        }
    }

    private fun deleteSessionReadings(sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.deleteMappedReadingsBySessionId(sessionId)
            refreshSessions()
        }
    }

    private fun showSession(sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val mapped = db.getAllMapped(sessionId)

            lifecycleScope.launch(Dispatchers.Main) {
                var csv = ""
                val sessionTableLayout = findViewById<TableLayout>(R.id.session)
                sessionTableLayout.removeAllViews()

                mapped.map { it ->
                    val tableRow = TableRow(applicationContext)

                    val dateCol = TextView(this@MainActivity)
                    formatDateTime(it.unixTimestamp).also { dateCol.text = it }
                    tableRow.addView(dateCol)

                    val readingCol = TextView(this@MainActivity)
                    readingCol.setPaddingRelative(25, 0, 25, 0)
                    readingCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    it.reading.toString().also { readingCol.text = it }
                    tableRow.addView(readingCol)

                    val latitudeCol = TextView(this@MainActivity)
                    latitudeCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    Double.fromBits(it.latitude).toString().also { latitudeCol.text = it }
                    tableRow.addView(latitudeCol)

                    val longitudeCol = TextView(this@MainActivity)
                    longitudeCol.setPaddingRelative(25, 0, 25, 0)
                    longitudeCol.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    Double.fromBits(it.longitude).toString().also { longitudeCol.text = it }
                    tableRow.addView(longitudeCol)

                    sessionTableLayout.addView(tableRow)

                    if (it.sessionId == "28cb82a9-9f04-49e4-8cba-1fce19f57e8a") {
                        // TODO:: This format can be used on this website's bulk entry, but that website uses Open Street Map so maybe we can embed that in the app?
                        // https://www.mapcustomizer.com/
                        csv += "${Double.fromBits(it.latitude)}, ${Double.fromBits(it.longitude)} {${it.reading}}\n"
                    }
                }
                Log.d("MainActivity", csv)
            }
        }
    }

    private fun formatDate(seconds: Long): String {
        return SimpleDateFormat.getDateInstance(DateFormat.SHORT).format(java.util.Date(seconds * 1000))
    }

    /**
     * Hack to strip the comma from the formatted datetime string.
     * This is to work around an edge case bug in Google Sheets
     * where pasting csv data into a cell and using the "Split into columns" context menu item
     * mistakes a comma in the datetime column for separate date and time columns
     * even if the column value is wrapped in quotes.
     */
    private fun formatDateTimeForCsv(seconds: Long): String {
        return formatDateTime(seconds).replace(",", "")
    }

    private fun formatDateTime(seconds: Long): String {
        return SimpleDateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(java.util.Date(seconds * 1000))
    }

    @SuppressLint("SimpleDateFormat")
    private fun parseDateTime(dateTime: String): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        val date = formatter.parse(dateTime)
        return date.time / 1000
    }

    private fun relativeTime(unixTime: Long): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            unixTime * 1000,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private suspend fun getPreviousReadingsAsCsv(): String {
        val readings = db.getAll(previousReadingsFromUnixTimestamp, previousReadingsToUnixTimestamp)

        return (if (csvHasHeaderRow) CSV_PREVIOUS_READINGS_HEADERS else CSV_NO_HEADERS) +
            readings.joinToString(separator = "\n") {
                "\"${formatDateTimeForCsv(it.unixTimestamp)}\",${it.reading},${it.deltaIncrease},${it.deltaDecrease}"
            }
    }

    private fun processAtmosphericPressureMappingSession(): Int {
        val keptReadings: MutableList<AtmosphericPressureMappingSessionReading> = mutableListOf()
        var lastReading: AtmosphericPressureMappingSessionReading
        for (reading in mappedReadings) {
            // We want to keep the first two, since they're the first two points that form a line
            if (keptReadings.size < 2) {
                keptReadings.add(reading)
                continue
            }

            lastReading = keptReadings.last()

            // We want to keep this reading if it's been more than a minute since our last reading
            if ((reading.unixTimestamp - lastReading.unixTimestamp) >= 60) {
                keptReadings.add(reading)
                continue
            }

            // We want to keep this reading if the atmospheric pressure has increased by more than 2 millibars since the last reading
            if (abs(reading.reading - lastReading.reading) >= 2) {
                keptReadings.add(reading)
                continue
            }

            // It might be good to do something with directions
            // Each reading is basically an (x,y) point.
            // The most two kept readings most recently added make up the "previous heading"
            // The current reading and the most recently added kept reading make the "current heading"
            // A bit of trigonometry gives us the angle between the two headings.
            // Any angle larger than a pre-defined value is an interesting course change
            // so we keep the current reading
        }

        lifecycleScope.launch(Dispatchers.IO) {
            db.insertAll(*keptReadings.toTypedArray())
        }

        return keptReadings.size
    }

    override fun onLocationChanged(location: Location) {
        val unixTimestamp = location.time / 1000
        val reading = lastAtmosphericPressureReading
        val latitude = location.latitude.toRawBits()
        val longitude = location.longitude.toRawBits()
        mappedReadings.add(AtmosphericPressureMappingSessionReading(unixTimestamp, reading, latitude, longitude, sessionId))
    }

    private fun startMappingSession() {
        if (mappingInProgress) {
            endMappingSession()
            return
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor == null) {
            showToast(R.string.cannot_map_err_no_pressure_sensor)
            return
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (locationManager?.isLocationEnabled != true) {
            showToast(R.string.cannot_map_err_no_gps)
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray(),
                REQUEST_PERMISSION_LOCATION
            )

            showToast(R.string.cannot_map_err_no_gps)
            return
        }

        sessionId = UUID.randomUUID().toString()
        mappingInProgress = true
        mappedReadings.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showToast(R.string.mapping_session_started)
        lifecycleScope.launch(Dispatchers.Main) {
            findViewById<Button>(R.id.start_mapping_session).text = getString(R.string.mapping_in_progress)
        }

        sensorManager?.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            10F,
            this@MainActivity
        )
    }

    private fun endMappingSession() {
        locationManager?.removeUpdates(this)
        sensorManager?.unregisterListener(this, pressureSensor)

        if (mappingInProgress) {
            val numberOfKeptReadings = processAtmosphericPressureMappingSession()
            mappingInProgress = false
            mappedReadings.clear()
            showToast(R.string.mapping_session_ended, numberOfKeptReadings)

            lifecycleScope.launch(Dispatchers.Main) {
                findViewById<Button>(R.id.start_mapping_session).text = getString(R.string.start_mapping_session)
            }
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            lastAtmosphericPressureReading = event.values[0].roundToInt()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) { /* No-op */ }
}
