package com.example.finalapp2

// MainActivity.kt
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

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

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val photo = result.data?.extras?.get("data") as Bitmap
                thumbnail = photo
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "sensor-db").build()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setContent {
            var page by remember { mutableStateOf(0) } // 0=Main, 1=History

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
                        onStartServer = { startBluetoothServer() },
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

            lifecycleScope.launch {
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
    // BLUETOOTH SERVER (simplified)
    ///////////////////////////
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startBluetoothServer() {
        bluetoothAdapter ?: return
        lifecycleScope.launch {
            val serverSocket: BluetoothServerSocket? =
                bluetoothAdapter.listenUsingRfcommWithServiceRecord("SensorApp", appUuid)
            while (true) {
                try {
                    val socket: BluetoothSocket = serverSocket!!.accept()
                    // TODO: Handle incoming data from other devices
                } catch (_: IOException) {
                    break
                }
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
