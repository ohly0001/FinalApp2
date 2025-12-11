package com.example.finalapp2

/*
Part 1:

Create an Android application to be run on a phone or tablet that will simulate a bluetooth data recording app.
You should connect to these following sensors on the phone:

Ambient Light

Proximity Sensor

Picture Thumbnail  (Here is example code to return a bitmap:
https://developer.android.com/guide/components/intents-common#ImageCapture)

Each item should be displayed on different rows and the values should change
along with the change of lighting levels around the device.
The application should also have a button so start the server and to display a QR code with the application's
Bluetooth UUID. (3 marks)

2) Create a photo gathering app that lets you pair with another device to simultaneously record the other phone's sensors.
When another phone connects, another set of rows like in part 1 appears on the screen,
however it shows that it's from the other phone, along with that phone's device name and UUID. (1 mark)

3) There should be a "take photo" button that when pressed, sends all 4 values and the photo to a web server for recording
as a kind of "history" for that application, as well as stored locally in the database.
You can save a bitmap as a Base64-encoded string:
Step 3 in ( https://medium.com/@sh0707.lee/the-easiest-method-to-save-images-in-room-database-by-base64-encoding-9b697e47b6fa)(1 mark)

4) Both applications should have a second page in the application that shows a list of readings from the history in a
LazyColumn. Clicking on an item should show the details of the variables read, the time, and the other device information.
(1 mark)

Part 2:

3) Use the provided MSWord document to create a Google Playstore listing for the application.
Include short and full descriptions of the applications, 3 screenshots of the apps on a phone, on a tablet,
and answer all of the questions required for the listing. (1 mark)

4) Create signed APK of each application that you can sideload onto a phone or tablet. Also,
both applications should share a common theme file. (1 mark)

5) As part of your web server, add a default "/" endpoint that returns HTML with your privacy policy explaining what data
you collect and why you collect it. You should also display a list of the photos sent to the server as an HTML page as
part of that page. On the android devices, each application should have a "clear history" button
that empties the device's local history of data transfers, and sends a "clear history" command that gets sent to the
server to clear the server's history list. (2 marks)
 */

// MainActivity.kt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Date
import java.util.UUID

///////////////////////////
// ROOM DATABASE SETUP
///////////////////////////

@Entity
data class SensorHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val deviceName: String,
    val uuid: String,
    val ambientLight: Float,
    val proximity: Float,
    val timestamp: Long,
    val photoBase64: String
)

@Dao
interface SensorHistoryDao {
    @Insert
    suspend fun insert(history: SensorHistory)

    @Query("SELECT * FROM SensorHistory ORDER BY timestamp DESC")
    suspend fun getAll(): List<SensorHistory>
}

@Database(entities = [SensorHistory::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorHistoryDao(): SensorHistoryDao
}

///////////////////////////
// MAIN ACTIVITY
///////////////////////////

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null

    private var ambientLight by mutableStateOf(0f)
    private var proximity by mutableStateOf(0f)
    private var thumbnail by mutableStateOf<Bitmap?>(null)

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val appUuid: UUID = UUID.randomUUID()

    private lateinit var db: AppDatabase

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val havePermissions = mutableStateOf(false)

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val photo = result.data?.extras?.get("data") as Bitmap
                thumbnail = photo
            }
        }

    fun requestPermissions() {
        val requiredPermissions = if (Build.VERSION_CODES.S <= Build.VERSION.SDK_INT) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA
            )
        }

        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        // Request if not already granted
        if (allPermissionsGranted) {
            havePermissions.value = true
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            havePermissions.value = permissions.values.all { it }
        }

        requestPermissions()

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "sensor-db").build()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setContent {
            var page by remember { mutableIntStateOf(0) } // 0=Main, 1=History

            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("Bluetooth Sensor App") })
                },
                bottomBar = {
                    Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = { page = 0 }) { Text("Main") }
                        Button(onClick = { page = 1 }) { Text("History") }
                    }
                }
            ) {
                when (page) {
                    0 -> MainPage(
                        ambientLight,
                        proximity,
                        thumbnail,
                        onTakePhoto = { takePhoto() },
                        onStartServer = @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) { startBluetoothServer() },
                        uuid = appUuid.toString()
                    )
                    1 -> HistoryPage(db)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_LIGHT -> ambientLight = it.values[0]
                Sensor.TYPE_PROXIMITY -> proximity = it.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun takePhoto() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraLauncher.launch(intent)

        // Save to Room once photo is taken
        thumbnail?.let { bmp ->
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val base64Photo = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

            lifecycleScope.launch @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) {
                db.sensorHistoryDao().insert(
                    SensorHistory(
                        deviceName = bluetoothAdapter?.name ?: "LocalDevice",
                        uuid = appUuid.toString(),
                        ambientLight = ambientLight,
                        proximity = proximity,
                        timestamp = System.currentTimeMillis(),
                        photoBase64 = base64Photo
                    )
                )
            }

            // TODO: Send data + photoBase64 to remote server
        }
    }

    ///////////////////////////
    // BLUETOOTH SERVER
    ///////////////////////////

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startBluetoothServer() {
        bluetoothAdapter ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val serverSocket = bluetoothAdapter
                .listenUsingRfcommWithServiceRecord("SensorApp", appUuid)

            try {
                while (true) {
                    val socket = serverSocket.accept()  // this blocks fine on IO thread
                    // TODO handle connected socket (also off main thread)
                    // TODO: Handle incoming data from other devices
                }
            } catch (e: IOException) {
                // socket closed
            } finally {
                serverSocket.close()
            }
        }
    }
}

///////////////////////////
// COMPOSABLES
///////////////////////////

@Composable
fun MainPage(
    ambientLight: Float,
    proximity: Float,
    thumbnail: Bitmap?,
    onTakePhoto: () -> Unit,
    onStartServer: () -> Unit,
    uuid: String
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Ambient Light: $ambientLight lx")
        Text("Proximity: $proximity cm")
        thumbnail?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(100.dp)) }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onTakePhoto) { Text("Take Photo") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStartServer) { Text("Start Bluetooth Server") }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Bluetooth UUID: $uuid")
    }
}

@Composable
fun HistoryPage(db: AppDatabase) {
    var historyList by remember { mutableStateOf<List<SensorHistory>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            historyList = db.sensorHistoryDao().getAll()
        }
    }

    LazyColumn {
        items(historyList) { item ->
            Card(modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Device: ${item.deviceName}")
                    Text("UUID: ${item.uuid}")
                    Text("Timestamp: ${Date(item.timestamp)}")
                    Text("Ambient Light: ${item.ambientLight}")
                    Text("Proximity: ${item.proximity}")
                    // For photo, optionally add Image decoding
                }
            }
        }
    }
}
