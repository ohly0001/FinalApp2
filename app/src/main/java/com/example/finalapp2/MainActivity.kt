package com.example.finalapp2

/*
Part 1:

Create an Android application to be run on a phone or tablet that will simulate a bluetooth data recording app.
You should connect to these following sensors on the phone:

Ambient Light

Proximity Sensor

Picture Thumbnail  (Here is example code to return a bitmap:
http://developer.android.com/guide/components/intents-common#ImageCapture)

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
Step 3 in ( http://medium.com/@sh0707.lee/the-easiest-method-to-save-images-in-room-database-by-base64-encoding-9b697e47b6fa)(1 mark)

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

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.UUID

data class SensorHistory(
    val deviceName: String,
    val uuid: String,
    val ambientLight: Float,
    val proximity: Float,
    val timestamp: Long,
    val photoBase64: String,
    val isRemote: Boolean = false
)

///////////////////////////
// LOCAL STORAGE MANAGER
///////////////////////////

class LocalHistoryManager(private val context: ComponentActivity) {
    private val fileName = "sensor_history.json"

    fun loadHistory(): List<SensorHistory> {
        return try {
            val file = File(context.filesDir, fileName)
            if (!file.exists()) return emptyList()
            val jsonString = file.readText()
            val jsonArray = JSONObject("{\"array\":$jsonString}").getJSONArray("array")
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                SensorHistory(
                    deviceName = obj.getString("deviceName"),
                    uuid = obj.getString("uuid"),
                    ambientLight = obj.getDouble("ambientLight").toFloat(),
                    proximity = obj.getDouble("proximity").toFloat(),
                    timestamp = obj.getLong("timestamp"),
                    photoBase64 = obj.getString("photoBase64"),
                    isRemote = obj.optBoolean("isRemote", false)
                )
            }
        } catch (e: Exception) {
            Log.e("LocalHistoryManager", "Failed to load history: ${e.localizedMessage}")
            emptyList()
        }
    }

    fun saveHistory(list: List<SensorHistory>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { item ->
                val obj = JSONObject().apply {
                    put("deviceName", item.deviceName)
                    put("uuid", item.uuid)
                    put("ambientLight", item.ambientLight)
                    put("proximity", item.proximity)
                    put("timestamp", item.timestamp)
                    put("photoBase64", item.photoBase64)
                    put("isRemote", item.isRemote)
                }
                jsonArray.put(obj)
            }
            File(context.filesDir, fileName).writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e("LocalHistoryManager", "Failed to save history: ${e.localizedMessage}")
        }
    }

    fun clearHistory() {
        try {
            File(context.filesDir, fileName).delete()
        } catch (e: Exception) {
            Log.e("LocalHistoryManager", "Failed to clear history: ${e.localizedMessage}")
        }
    }
}

///////////////////////////
// MAIN ACTIVITY
///////////////////////////

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null

    private var ambientLight by mutableFloatStateOf(0f)
    private var proximity by mutableFloatStateOf(0f)
    private var thumbnail by mutableStateOf<Bitmap?>(null)

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val appUuid: UUID = UUID.randomUUID()

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val havePermissions = mutableStateOf(false)

    private val connectedRemoteDevices =
        mutableStateListOf<SensorHistory>() // show remote device rows

    private var showQrDialog by mutableStateOf(false)
    private var qrBitmap by mutableStateOf<Bitmap?>(null)

    private lateinit var historyManager: LocalHistoryManager

    private lateinit var qrScanLauncher: ActivityResultLauncher<Intent>
    private var clientSocket: BluetoothSocket? = null
    private var remoteUuid: UUID? = null
    private var isClientActive = mutableStateOf(false)
    private var scanningQr = mutableStateOf(false)
    private val liveRemoteDevice = mutableStateOf<SensorHistory?>(null)

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val photo = result.data?.extras?.get("data") as? Bitmap
                thumbnail = photo
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            havePermissions.value = permissions.values.all { it }
        }
        requestPermissions()

        historyManager = LocalHistoryManager(this)
        connectedRemoteDevices.addAll(historyManager.loadHistory()) // load persisted history

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        qrScanLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val contents = result.data?.getStringExtra("SCAN_RESULT")
                if (!contents.isNullOrBlank()) {
                    try {
                        remoteUuid = UUID.fromString(contents)
                        lifecycleScope.launch(Dispatchers.IO) {
                            connectToRemoteDevice(remoteUuid!!)
                        }
                    } catch (e: Exception) {
                        Log.e("QR", "Invalid UUID scanned: ${e.localizedMessage}")
                    }
                }
            }
        }

        setContent {
            var page by remember { mutableIntStateOf(0) } // 0=Main, 1=History

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                //removed due to it obstructing the top of the menu
                //topBar = {
                //    TopAppBar(title = { Text("Bluetooth Sensor App") })
                //},
                bottomBar = {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
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
                        connectedRemoteDevices,
                        liveRemoteDevice,
                        onTakePhoto = { takePhotoAndUpload() },
                        onStartServer = @RequiresPermission(
                            allOf = [
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE
                            ]
                        ) { startBluetoothServerAndShowQr() },
                        uuid = appUuid.toString(),
                        onClearHistory = { clearHistory() },
                        showQrDialog = showQrDialog,
                        qrBitmap = qrBitmap,
                        onDismissQr = { showQrDialog = false },
                        onScanQr = { scanningQr.value = true },
                        isClientActive = isClientActive.value)
                    1 -> HistoryPage(
                        onClearHistory = { clearHistory() },
                        historyManager = historyManager
                    )
                }
            }

            if (scanningQr.value) {
                QrScanner(
                    onQrScanned = { scannedValue ->
                        scanningQr.value = false
                        try {
                            val qrJson = JSONObject(scannedValue)
                            val mac = qrJson.getString("mac")
                            val uuid = UUID.fromString(qrJson.getString("uuid"))

                            lifecycleScope.launch(Dispatchers.IO) {
                                connectToRemoteDevice(mac, uuid)
                            }
                        } catch (e: Exception) {
                            Log.e("QR", "Invalid QR content: ${e.localizedMessage}")
                        }
                    },
                    onClose = { scanningQr.value = false }
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToRemoteDevice(mac: String, uuid: UUID) {
        try {
            val adapter = bluetoothAdapter ?: return
            val device = adapter.getRemoteDevice(mac)
            Log.i("BT-Client", "Connecting to device ${device.name} at $mac with UUID $uuid")

            clientSocket = device.createRfcommSocketToServiceRecord(uuid)

            // connect in IO dispatcher
            clientSocket?.connect()
            Log.i("BT-Client", "Connected to ${device.name}")
            isClientActive.value = true

            // start streaming local sensors
            lifecycleScope.launch(Dispatchers.IO) {
                clientSocket?.let { socket ->
                    streamLocalSensorsToRemote(socket)
                }
            }

        } catch (e: Exception) {
            Log.e("BT-Client", "Client connection failed: ${e.localizedMessage}")
            isClientActive.value = false
            clientSocket?.close()
        }
    }

    suspend fun streamLocalSensorsToRemote(socket: BluetoothSocket) {
        try {
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))

            while (true) {
                val bmpBase64 = thumbnail?.let { bmp ->
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
                } ?: ""

                val json = JSONObject().apply {
                    put("deviceName", bluetoothAdapter?.name ?: "ClientDevice")
                    put("uuid", appUuid.toString())
                    put("ambientLight", ambientLight)
                    put("proximity", proximity)
                    put("timestamp", System.currentTimeMillis())
                    put("photoBase64", bmpBase64)
                }

                writer.write(json.toString() + "\n")
                writer.flush()

                delay(300) // rate of sensor updates
            }
        } catch (e: Exception) {
            Log.e("BT-Stream", "Stream ended: ${e.localizedMessage}")
            isClientActive.value = false
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = if (Build.VERSION_CODES.S <= Build.VERSION.SDK_INT) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET
            )
        }

        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            havePermissions.value = true
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        proximitySensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
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

    ///////////////////////////
    // BLUETOOTH SERVER
    ///////////////////////////

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ]
    )
    private fun startBluetoothServerAndShowQr() {
        bluetoothAdapter ?: return

        // spawn the server
        lifecycleScope.launch(Dispatchers.IO) {
            startBluetoothServer()
        }

        // generate QR for UUID + MAC
        lifecycleScope.launch {
            val macAddress = bluetoothAdapter.address ?: "00:00:00:00:00:00"
            val qrJson = JSONObject().apply {
                put("uuid", appUuid.toString())
                put("mac", macAddress)
            }
            val bitmap = generateQrBitmap(qrJson.toString())
            qrBitmap = bitmap
            showQrDialog = true
        }
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ]
    )
    private fun startBluetoothServer() {
        bluetoothAdapter ?: return
        val serviceName = "SensorApp"

        lifecycleScope.launch(Dispatchers.IO) {
            var serverSocket: BluetoothServerSocket? = null
            try {
                serverSocket =
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(serviceName, appUuid)

                while (true) {
                    val socket: BluetoothSocket = serverSocket.accept() ?: break
                    Log.i("MainActivity", "Accepted connection from ${socket.remoteDevice?.name}")

                    // Handle each connected socket on its own coroutine
                    launch(Dispatchers.IO) {
                        handleConnectedSocket(socket)
                    }
                }
            } catch (e: IOException) {
                Log.e("MainActivity", "Server socket error: ${e.localizedMessage}")
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    ///////////////////////////
    // HTTP POST to server
    ///////////////////////////

    /**
     * Posts the SensorHistory as JSON to server /upload.
     * Server must accept application/json with fields matching SensorHistory.
     * (Adapt URL to your server)
     */
    private fun postToServer(item: SensorHistory) {
        val serverUrl = "http://10.0.2.2:8080/history" // updated endpoint
        val json = JSONObject().apply {
            put("payload", JSONObject().apply {
                put("deviceName", item.deviceName)
                put("uuid", item.uuid)
                put("ambientLight", item.ambientLight)
                put("proximity", item.proximity)
                put("timestamp", item.timestamp)
                put("photoBase64", item.photoBase64)
            })
        }

        var conn: HttpURLConnection? = null
        try {
            val url = URL(serverUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            Log.i("MainActivity", "Server response code: $responseCode")

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            Log.d("MainActivity", "Server response: $response")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error POSTing to server: ${e.localizedMessage}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Send "clear history" command to server. Server must expose an endpoint that clears server-side list.
     */
    private fun requestServerClearHistory() {
        val serverUrl = "http://10.0.2.2:8080/history/all" // updated endpoint
        lifecycleScope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(serverUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val responseCode = conn.responseCode
                Log.i("MainActivity", "Server clear response: $responseCode")
            } catch (e: Exception) {
                Log.e("MainActivity", "clear history request failed: ${e.localizedMessage}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    ///////////////////////////
    // QR CODE GENERATION
    ///////////////////////////

    private fun generateQrBitmap(text: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height)
            val bmp = createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp[x, y] = if (bitMatrix.get(
                            x,
                            y
                        )
                    ) Color.BLACK else Color.WHITE
                }
            }
            bmp
        } catch (e: Exception) {
            Log.e("MainActivity", "QR generation failed: ${e.localizedMessage}")
            null
        }
    }

    private fun takePhotoAndUpload() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraLauncher.launch(intent)

        lifecycleScope.launch {
            thumbnail?.let { bmp ->
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val base64Photo = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                val item = SensorHistory(
                    deviceName = bluetoothAdapter?.name ?: "LocalDevice",
                    uuid = appUuid.toString(),
                    ambientLight = ambientLight,
                    proximity = proximity,
                    timestamp = System.currentTimeMillis(),
                    photoBase64 = base64Photo,
                    isRemote = false // only local readings
                )

                // add to local list and save
                addNewHistory(item)

                // send **only local readings** to server
                launch(Dispatchers.IO) {
                    try {
                        postToServer(item)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }


    /**
     * Expect JSON messages from peer devices, one JSON object per line.
     * JSON structure example:
     * {
     *  "deviceName":"Phone B",
     *  "uuid":"some-uuid-string",
     *  "ambientLight":123.4,
     *  "proximity":0.0,
     *  "photoBase64":"....",
     *  "timestamp": 1630000000000
     * }
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleConnectedSocket(socket: BluetoothSocket) {
        val remoteDeviceName = socket.remoteDevice?.name ?: "Remote"
        try {
            val input = BufferedReader(InputStreamReader(socket.inputStream))
            var line: String? = input.readLine()
            while (line != null) {
                try {
                    val json = JSONObject(line)
                    val remoteHistory = SensorHistory(
                        deviceName = json.optString("deviceName", remoteDeviceName),
                        uuid = json.optString("uuid", "unknown"),
                        ambientLight = json.optDouble("ambientLight", 0.0).toFloat(),
                        proximity = json.optDouble("proximity", 0.0).toFloat(),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        photoBase64 = json.optString("photoBase64", ""),
                        isRemote = true
                    )

                    // Update live remote device
                    lifecycleScope.launch(Dispatchers.Main) {
                        liveRemoteDevice.value = remoteHistory
                    }

                    // Optionally add to history list (if you want server posting)
                    lifecycleScope.launch(Dispatchers.Main) {
                        connectedRemoteDevices.add(0, remoteHistory)
                        historyManager.saveHistory(connectedRemoteDevices.toList())
                    }

                } catch (_: Exception) { }
                line = input.readLine()
            }
        } catch (_: Exception) { }
    }

    private fun clearHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            historyManager.clearHistory()
            connectedRemoteDevices.clear()
            requestServerClearHistory()
        }
    }

    private fun addNewHistory(item: SensorHistory) {
        connectedRemoteDevices.add(0, item)
        historyManager.saveHistory(connectedRemoteDevices.toList())
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
    connectedRemoteDevices: SnapshotStateList<SensorHistory>,
    liveRemoteDevice: State<SensorHistory?>,
    onTakePhoto: () -> Unit,
    onStartServer: () -> Unit,
    uuid: String,
    onClearHistory: () -> Unit,
    showQrDialog: Boolean,
    qrBitmap: Bitmap?,
    onDismissQr: () -> Unit,
    onScanQr: () -> Unit,
    isClientActive: Boolean
) {
    Column(modifier = Modifier.padding(16.dp)) {
        if (isClientActive) {
            Text("Connected as Client", color = MaterialTheme.colorScheme.primary)
        }
        Text("Local Device", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Ambient Light: $ambientLight lx")
        Text("Proximity: $proximity")
        thumbnail?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(150.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row {
            Button(onClick = onTakePhoto) { Text("Take Photo & Upload") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onStartServer) { Text("Start Bluetooth Server & Show QR") }
        }
        Row {
            Button(onClick = onScanQr) { Text("Scan QR to Connect") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onClearHistory) { Text("Clear History (local + server)") }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Bluetooth UUID: $uuid")

        Spacer(modifier = Modifier.height(12.dp))
        liveRemoteDevice.value?.let { remote ->
            Text("Live Remote Device: ${remote.deviceName}", color = MaterialTheme.colorScheme.primary)
            Text("Ambient Light: ${remote.ambientLight}")
            Text("Proximity: ${remote.proximity}")
            if (remote.photoBase64.isNotEmpty()) {
                val bytes = Base64.decode(remote.photoBase64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bmp?.let {
                    Image(it.asImageBitmap(), contentDescription = "Remote Photo", modifier = Modifier.size(150.dp))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
        if (connectedRemoteDevices.isNotEmpty()) {
            Text("Connected Remote Devices", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(connectedRemoteDevices) { item ->
                    DeviceCard(item)
                }
            }
        }

        if (showQrDialog && qrBitmap != null) {
            AlertDialog(
                onDismissRequest = onDismissQr,
                confirmButton = {
                    Button(onClick = onDismissQr) { Text("Close") }
                },
                title = { Text("App UUID QR Code") },
                text = {
                    Column {
                        Text("Scan this QR on another device to connect (contains the UUID).")
                        Spacer(modifier = Modifier.height(8.dp))
                        Image(
                            qrBitmap.asImageBitmap(),
                            contentDescription = "QR",
                            modifier = Modifier.size(250.dp)
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun DeviceCard(item: SensorHistory) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Device: ${item.deviceName}")
            Text("UUID: ${item.uuid}")
            Text("Timestamp: ${Date(item.timestamp)}")
            Text("Ambient Light: ${item.ambientLight}")
            Text("Proximity: ${item.proximity}")
            if (item.photoBase64.isNotEmpty()) {
                val bytes = Base64.decode(item.photoBase64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bmp?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryPage(
    onClearHistory: () -> Unit,
    historyManager: LocalHistoryManager
) {
    var historyList by remember { mutableStateOf<List<SensorHistory>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Load the history from file when the composable is first displayed
    LaunchedEffect(Unit) {
        historyList = historyManager.loadHistory()
    }

    Column {
        Row(modifier = Modifier.padding(8.dp)) {
            Button(onClick = {
                scope.launch {
                    // Clear history in file and in memory
                    historyManager.clearHistory()
                    onClearHistory()
                    historyList = emptyList()
                }
            }) {
                Text("Clear Local History")
            }
        }

        LazyColumn {
            items(historyList) { item ->
                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Device: ${item.deviceName}")
                        Text("UUID: ${item.uuid}")
                        Text("Timestamp: ${Date(item.timestamp)}")
                        Text("Ambient Light: ${item.ambientLight}")
                        Text("Proximity: ${item.proximity}")
                        if (item.photoBase64.isNotEmpty()) {
                            val bytes = Base64.decode(item.photoBase64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            bmp?.let {
                                Image(
                                    it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(120.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanner(
    onQrScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box {
        AndroidView(factory = { ctx ->
            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.surfaceProvider = previewView.surfaceProvider

                val barcodeScanner = BarcodeScanning.getClient()

                val analyzer = ImageAnalysis.Builder().build().also { analysis ->
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy: ImageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        barcode.rawValue?.let { value ->
                                            onQrScanned(value)
                                            imageProxy.close()
                                            return@addOnSuccessListener
                                        }
                                    }
                                }
                                .addOnFailureListener { }
                                .addOnCompleteListener { imageProxy.close() }
                        }
                    }
                }

                try {
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analyzer)
                } catch (exc: Exception) {
                    Log.e("QrScanner", "Camera bind failed: ${exc.localizedMessage}")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }, modifier = Modifier.fillMaxSize())
    }
}