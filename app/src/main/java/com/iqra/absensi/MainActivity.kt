package com.iqra.absensi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.iqra.absensi.ui.theme.AbsensiLokasiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/* ------------------- DataStore ------------------- */
private val ComponentActivity.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val THEME_KEY = stringPreferencesKey("theme_mode")
private val AUTO_ABSEN_KEY = booleanPreferencesKey("auto_absen")

class MainActivity : ComponentActivity() {

    /* ===== Emulator (opsional) ===== */
    private val USE_EMULATOR = false
    private val EMULATOR_HOST = "10.128.105.178"
    private val EMULATOR_PORT = 8080

    /* ===== Kantor (ITB Nobel Indonesia) ===== */
    private val OFFICE_LAT = -5.1787531
    private val OFFICE_LNG = 119.4390442
    private val OFFICE_RADIUS_M = 100.0

    private val companyId = "cmpA"

    private lateinit var auth: FirebaseAuth
    private val db by lazy { Firebase.firestore }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false // debounce absen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (USE_EMULATOR) db.useEmulator(EMULATOR_HOST, EMULATOR_PORT)
        auth = Firebase.auth

        setContent {
            val scope = rememberCoroutineScope()

            val themeMode by dataStore.data
                .map { pref ->
                    val v = pref[THEME_KEY] ?: ThemeMode.SYSTEM.name
                    runCatching { ThemeMode.valueOf(v) }.getOrDefault(ThemeMode.SYSTEM)
                }
                .collectAsState(initial = ThemeMode.SYSTEM)

            val autoAbsen by dataStore.data
                .map { pref -> pref[AUTO_ABSEN_KEY] ?: true }
                .collectAsState(initial = true)

            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            AbsensiLokasiTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    val loggedIn = rememberAuthState(auth)

                    Crossfade(targetState = loggedIn, label = "authGate") { isIn ->
                        if (isIn) {
                            AttendanceScreen(
                                uid = auth.currentUser?.uid.orEmpty(),
                                email = auth.currentUser?.email.orEmpty(),
                                companyId = companyId,
                                officeLat = OFFICE_LAT,
                                officeLng = OFFICE_LNG,
                                officeRadiusM = OFFICE_RADIUS_M,
                                autoAbsen = autoAbsen,
                                onAutoAbsenChange = { v -> scope.launch { saveAutoAbsen(v) } },
                                onCheckIn = { doAttendance("IN") },
                                onCheckOut = { doAttendance("OUT") },
                                onLogout = { auth.signOut() },
                                themeMode = themeMode,
                                onThemeChange = { mode -> scope.launch { saveTheme(mode) } }
                            )
                        } else {
                            // ✅ REGISTER DIHAPUS: hanya login
                            AuthScreen(
                                onLogin = { e, p -> signIn(e, p) },
                                themeMode = themeMode,
                                onThemeChange = { mode -> scope.launch { saveTheme(mode) } }
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveTheme(mode: ThemeMode) {
        dataStore.edit { it[THEME_KEY] = mode.name }
    }

    private suspend fun saveAutoAbsen(v: Boolean) {
        dataStore.edit { it[AUTO_ABSEN_KEY] = v }
    }

    /* ---------------- Auth (LOGIN ONLY) ---------------- */

    private fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener {
                toast("Login OK")
                ensureEmployeeProfileExists()
            }
            .addOnFailureListener { e -> toast("Login gagal: ${e.message}") }
    }

    /** bikin dokumen employees/{uid} kalau belum ada (user lama / akun dibuat admin) */
    private fun ensureEmployeeProfileExists() {
        val user = auth.currentUser ?: return
        val uid = user.uid
        val em = user.email.orEmpty()

        val docRef = db.collection("companies").document(companyId)
            .collection("employees").document(uid)

        docRef.get().addOnSuccessListener { snap ->
            if (snap != null && snap.exists()) return@addOnSuccessListener

            val defaultName = user.displayName?.takeIf { it.isNotBlank() }
                ?: em.substringBefore("@").ifBlank { "User" }

            val profile = mapOf(
                "uid" to uid,
                "name" to defaultName,
                "email" to em,
                "createdAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now()
            )
            docRef.set(profile, SetOptions.merge())
        }
    }

    /* ---------------- Attendance core ---------------- */

    data class TodayState(
        val cycle: Int = 0,
        val lastType: String? = null,
        val lastIn: Timestamp? = null,
        val lastOut: Timestamp? = null
    ) {
        val canCheckIn: Boolean get() = (lastType == null || lastType == "OUT")
        val canCheckOut: Boolean get() = (lastType == "IN")
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val s1 = sin(dLat / 2) * sin(dLat / 2)
        val s2 = cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(s1 + s2), sqrt(1 - s1 - s2))
        return R * c
    }

    private fun doAttendance(type: String) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) { toast("Harus login dulu"); return }

        if (busy) return
        busy = true

        fun releaseBusyLater() {
            handler.postDelayed({ busy = false }, 900)
        }

        val fineGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
            toast("Izin lokasi diminta, tekan lagi setelah diizinkan.")
            releaseBusyLater()
            return
        }

        fetchTodayState(uid) { st ->
            when (type) {
                "IN" -> if (!st.canCheckIn) { toast("Kamu masih status MASUK, pulang dulu."); releaseBusyLater(); return@fetchTodayState }
                "OUT" -> if (!st.canCheckOut) { toast("Kamu belum absen masuk."); releaseBusyLater(); return@fetchTodayState }
            }
            captureAndSave(uid, type) { releaseBusyLater() }
        }
    }

    private fun fetchTodayState(uid: String, cb: (TodayState) -> Unit) {
        val dateKey = todayKey()
        db.collection("companies").document(companyId)
            .collection("attendance")
            .whereEqualTo("employeeId", uid)
            .whereEqualTo("dateKey", dateKey)
            .get()
            .addOnSuccessListener { qs ->
                var cycle = 0
                var lastType: String? = null
                var lastTs: Timestamp? = null
                var lastIn: Timestamp? = null
                var lastOut: Timestamp? = null

                qs.documents.forEach { d ->
                    val t = d.getTimestamp("ts")
                    val type = d.getString("type")
                    if (type == "IN") cycle += 1

                    if (t != null && (lastTs == null || t.seconds > lastTs!!.seconds)) {
                        lastTs = t
                        lastType = type
                    }
                    if (type == "IN") lastIn = t ?: lastIn
                    if (type == "OUT") lastOut = t ?: lastOut
                }
                cb(TodayState(cycle, lastType, lastIn, lastOut))
            }
            .addOnFailureListener { cb(TodayState()) }
    }

    @SuppressLint("MissingPermission")
    private fun captureAndSave(uid: String, type: String, done: () -> Unit) {
        val fused = LocationServices.getFusedLocationProviderClient(this)
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .build()

        fused.getCurrentLocation(req, null)
            .addOnSuccessListener { loc ->
                if (loc == null) { toast("Lokasi null"); done(); return@addOnSuccessListener }

                if (loc.isFromMockProvider) { toast("Mock location terdeteksi. Absen ditolak."); done(); return@addOnSuccessListener }

                val dist = distanceMeters(loc.latitude, loc.longitude, OFFICE_LAT, OFFICE_LNG)
                if (dist > OFFICE_RADIUS_M) {
                    toast("Di luar area kantor (${dist.toInt()} m). Radius ${OFFICE_RADIUS_M.toInt()} m.")
                    done()
                    return@addOnSuccessListener
                }

                Thread {
                    val address = runCatching {
                        val geo = Geocoder(this, Locale.getDefault())
                        @Suppress("DEPRECATION")
                        geo.getFromLocation(loc.latitude, loc.longitude, 1)
                            ?.firstOrNull()
                            ?.getAddressLine(0)
                    }.getOrNull().orEmpty()

                    runOnUiThread {
                        val data = hashMapOf(
                            "employeeId" to uid,
                            "type" to type,
                            "dateKey" to todayKey(),
                            "lat" to loc.latitude,
                            "lng" to loc.longitude,
                            "accuracy" to loc.accuracy,
                            "distance" to dist,
                            "address" to address,
                            "device" to Build.MODEL,
                            "ts" to Timestamp.now()
                        )

                        db.collection("companies").document(companyId)
                            .collection("attendance")
                            .add(data)
                            .addOnSuccessListener {
                                toast("Absen $type berhasil ✅ (${dist.toInt()} m)")
                                done()
                            }
                            .addOnFailureListener { e ->
                                toast("Gagal simpan absen: ${e.message}")
                                done()
                            }
                    }
                }.start()
            }
            .addOnFailureListener { e ->
                toast(e.message ?: "Gagal ambil lokasi")
                done()
            }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/* ------------------- UI ------------------- */

private data class EmployeeProfile(
    val name: String = "",
    val email: String = ""
)

@Composable
private fun AuthScreen(
    onLogin: (email: String, pass: String) -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val ctx = LocalContext.current
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Login",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Masuk untuk mulai absen.",
                    style = MaterialTheme.typography.bodySmall
                )

                ThemeToggleRow(themeMode, onThemeChange)

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val e = email.trim()
                        val p = pass
                        if (e.isBlank() || p.isBlank()) {
                            Toast.makeText(ctx, "Email & password wajib diisi", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (p.length < 6) {
                            Toast.makeText(ctx, "Password minimal 6 karakter", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        onLogin(e, p)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("Login", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private data class LocInfo(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val address: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttendanceScreen(
    uid: String,
    email: String,
    companyId: String,
    officeLat: Double,
    officeLng: Double,
    officeRadiusM: Double,
    autoAbsen: Boolean,
    onAutoAbsenChange: (Boolean) -> Unit,
    onCheckIn: () -> Unit,
    onCheckOut: () -> Unit,
    onLogout: () -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val db = Firebase.firestore

    val dateKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    val dateTimeFmt = remember { SimpleDateFormat("EEE, dd MMM • HH:mm", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var profile by remember { mutableStateOf(EmployeeProfile()) }
    var editName by remember { mutableStateOf("") }

    var pendingSync by remember { mutableStateOf(false) }
    var todayDocs by remember { mutableStateOf(listOf<Map<String, Any?>>()) }
    var history by remember { mutableStateOf(listOf<Map<String, Any?>>()) }

    var myLoc by remember { mutableStateOf<LocInfo?>(null) }
    var officeAddr by remember { mutableStateOf<String?>(null) }
    var myDistanceM by remember { mutableStateOf<Int?>(null) }

    var lastAutoKey by remember { mutableStateOf<String?>(null) }

    fun openMaps(lat: Double, lng: Double) {
        val url = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { context.startActivity(i) }
    }

    fun computeDistanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Int {
        val R = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val s1 = sin(dLat / 2) * sin(dLat / 2)
        val s2 = cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(s1 + s2), sqrt(1 - s1 - s2))
        return (R * c).toInt()
    }

    suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            val geo = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            geo.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun refreshMyLocation() {
        val fineGranted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            activity?.let {
                ActivityCompat.requestPermissions(
                    it,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    200
                )
            }
            Toast.makeText(context, "Izin lokasi diminta. Tekan Refresh lagi setelah diizinkan.", Toast.LENGTH_LONG).show()
            return
        }

        val fused = LocationServices.getFusedLocationProviderClient(context)
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .build()

        fused.getCurrentLocation(req, null)
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener
                scope.launch {
                    val addr = reverseGeocode(loc.latitude, loc.longitude)
                    val dist = computeDistanceMeters(loc.latitude, loc.longitude, officeLat, officeLng)
                    myLoc = LocInfo(loc.latitude, loc.longitude, loc.accuracy, addr)
                    myDistanceM = dist
                }
            }
    }

    DisposableEffect(uid) {
        val reg = db.collection("companies").document(companyId)
            .collection("employees").document(uid)
            .addSnapshotListener { snap, _ ->
                val name = snap?.getString("name").orEmpty()
                val em = snap?.getString("email").orEmpty()

                profile = EmployeeProfile(
                    name = name,
                    email = if (em.isNotBlank()) em else email
                )

                editName = name
            }
        onDispose { reg.remove() }
    }

    LaunchedEffect(Unit) {
        officeAddr = reverseGeocode(officeLat, officeLng)
        refreshMyLocation()
    }

    DisposableEffect(uid, dateKey) {
        val reg = db.collection("companies").document(companyId)
            .collection("attendance")
            .whereEqualTo("employeeId", uid)
            .whereEqualTo("dateKey", dateKey)
            .addSnapshotListener { qs, _ ->
                if (qs == null) return@addSnapshotListener
                pendingSync = qs.metadata.hasPendingWrites()
                todayDocs = qs.documents.map { it.data ?: emptyMap() }
            }
        onDispose { reg.remove() }
    }

    DisposableEffect(uid) {
        val reg = db.collection("companies").document(companyId)
            .collection("attendance")
            .whereEqualTo("employeeId", uid)
            .orderBy("ts", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { qs, _ ->
                history = qs?.documents?.map { it.data ?: emptyMap() } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    var cycle = 0
    var lastType: String? = null
    var lastTs: Timestamp? = null
    var lastIn: Timestamp? = null
    var lastOut: Timestamp? = null
    var inCount = 0
    var outCount = 0

    todayDocs.forEach { d ->
        val type = d["type"] as? String
        val ts = d["ts"] as? Timestamp
        if (type == "IN") { cycle += 1; inCount += 1 }
        if (type == "OUT") outCount += 1

        if (ts != null && (lastTs == null || ts.seconds > lastTs!!.seconds)) {
            lastTs = ts
            lastType = type
        }
        if (type == "IN") lastIn = ts ?: lastIn
        if (type == "OUT") lastOut = ts ?: lastOut
    }

    val canCheckIn = (lastType == null || lastType == "OUT")
    val canCheckOut = (lastType == "IN")

    val insideOffice = myDistanceM?.let { it <= officeRadiusM.toInt() } == true

    LaunchedEffect(insideOffice, canCheckIn, canCheckOut, autoAbsen, myDistanceM, lastType, cycle) {
        if (!autoAbsen) return@LaunchedEffect
        if (!insideOffice) return@LaunchedEffect

        val target = when {
            canCheckIn -> "IN"
            canCheckOut -> "OUT"
            else -> null
        } ?: return@LaunchedEffect

        val key = "$dateKey|$target|$cycle|$lastType"
        if (lastAutoKey == key) return@LaunchedEffect
        lastAutoKey = key

        Toast.makeText(context, "Di radius kantor ✅ Auto absen $target…", Toast.LENGTH_SHORT).show()
        if (target == "IN") onCheckIn() else onCheckOut()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Absensi Lokasi") },
                actions = { TextButton(onClick = onLogout) { Text("Logout") } }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Profil", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                        val nameShow = profile.name.ifBlank { "Nama belum diisi" }
                        Text(nameShow, fontWeight = FontWeight.SemiBold)
                        Text(profile.email.ifBlank { email }, style = MaterialTheme.typography.bodySmall)
                        Text("UID: ${uid.take(6)}…${uid.takeLast(6)}", style = MaterialTheme.typography.bodySmall)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text("IN: $inCount") })
                            AssistChip(onClick = {}, label = { Text("OUT: $outCount") })
                            AssistChip(onClick = {}, label = { Text("Siklus: ${cycle.coerceAtLeast(0)}") })
                        }

                        Text("Masuk: " + (lastIn?.toDate()?.let { timeFmt.format(it) } ?: "—"))
                        Text("Pulang: " + (lastOut?.toDate()?.let { timeFmt.format(it) } ?: "—"))

                        ThemeToggleRow(themeMode, onThemeChange)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto absen", modifier = Modifier.weight(1f))
                            Switch(checked = autoAbsen, onCheckedChange = onAutoAbsenChange)
                        }

                        if (pendingSync) {
                            AssistChip(onClick = {}, label = { Text("Menunggu sinkron…") })
                        }

                        Divider()

                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Nama tampil") },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val newName = editName.trim()
                                if (newName.isBlank()) return@Button
                                db.collection("companies").document(companyId)
                                    .collection("employees").document(uid)
                                    .set(
                                        mapOf(
                                            "uid" to uid,
                                            "name" to newName,
                                            "email" to (profile.email.ifBlank { email }),
                                            "updatedAt" to Timestamp.now()
                                        ),
                                        SetOptions.merge()
                                    )
                                Toast.makeText(context, "Nama disimpan ✅", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Simpan Nama") }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Lokasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = { refreshMyLocation() }) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Refresh")
                            }
                        }

                        Text("Kamu:", fontWeight = FontWeight.SemiBold)
                        Text(
                            myLoc?.address ?: "Belum ada lokasi (cek permission/GPS).",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        myLoc?.let {
                            Text("Koordinat: ${"%.6f".format(it.lat)}, ${"%.6f".format(it.lng)}", style = MaterialTheme.typography.bodySmall)
                            Text("Akurasi: ${it.accuracyM?.toInt() ?: "-"} m", style = MaterialTheme.typography.bodySmall)
                        }

                        Divider()

                        Text("Kantor:", fontWeight = FontWeight.SemiBold)
                        Text(
                            officeAddr ?: "Memuat alamat kantor…",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("Titik Koordinat: ${"%.6f".format(officeLat)}, ${"%.6f".format(officeLng)}", style = MaterialTheme.typography.bodySmall)

                        val distText = myDistanceM?.let { "$it m" } ?: "—"
                        val statusText = if (insideOffice) "Di dalam radius ✅" else "Di luar radius ❌"
                        Text(
                            "Jarak ke kantor: $distText • $statusText (radius ${officeRadiusM.toInt()} m)",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = { openMaps(officeLat, officeLng) }) {
                                Text("Maps Kantor")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = myLoc != null,
                                onClick = { myLoc?.let { openMaps(it.lat, it.lng) } }
                            ) { Text("Maps Saya") }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onCheckIn,
                    enabled = canCheckIn,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    val label = if (cycle >= 2 && canCheckIn) "Absen Masuk (siklus ${cycle + 1})" else "Absen Masuk"
                    Text(label, fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                Button(
                    onClick = onCheckOut,
                    enabled = canCheckOut,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Absen Pulang", fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                Text(
                    when {
                        lastType == null -> "Belum absen hari ini."
                        lastType == "IN" -> "Status: sudah MASUK, silakan pulang saat selesai."
                        else -> "Status: sudah PULANG, boleh masuk lagi jika perlu."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                Divider()
                Text("Riwayat detail (terbaru)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            items(history) { row ->
                val t = row["ts"] as? Timestamp
                val type = row["type"] as? String ?: "?"
                val addr = (row["address"] as? String).orEmpty()
                val dist = (row["distance"] as? Number)?.toInt()
                val acc = (row["accuracy"] as? Number)?.toInt()
                val lat = (row["lat"] as? Number)?.toDouble()
                val lng = (row["lng"] as? Number)?.toDouble()

                Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = if (type == "IN") "Masuk" else "Pulang", fontWeight = FontWeight.SemiBold)
                        Text(text = t?.toDate()?.let { dateTimeFmt.format(it) } ?: "-", style = MaterialTheme.typography.bodySmall)

                        if (addr.isNotBlank()) {
                            Text(
                                text = addr,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val meta = buildString {
                            dist?.let { append("Jarak: ${it}m  ") }
                            acc?.let { append("Akurasi: ${it}m  ") }
                            if (lat != null && lng != null) append("(${String.format("%.5f", lat)}, ${String.format("%.5f", lng)})")
                        }
                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeToggleRow(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Tampilan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current == ThemeMode.LIGHT,
                onClick = { onChange(ThemeMode.LIGHT) },
                label = { Text("Light") },
                leadingIcon = { Icon(Icons.Default.LightMode, null) }
            )
            FilterChip(
                selected = current == ThemeMode.DARK,
                onClick = { onChange(ThemeMode.DARK) },
                label = { Text("Dark") },
                leadingIcon = { Icon(Icons.Default.DarkMode, null) }
            )
            FilterChip(
                selected = current == ThemeMode.SYSTEM,
                onClick = { onChange(ThemeMode.SYSTEM) },
                label = { Text("System") }
            )
        }
    }
}

@Composable
private fun rememberAuthState(auth: FirebaseAuth): Boolean {
    var loggedIn by remember { mutableStateOf(auth.currentUser != null) }
    DisposableEffect(auth) {
        val l = FirebaseAuth.AuthStateListener { fb -> loggedIn = fb.currentUser != null }
        auth.addAuthStateListener(l)
        onDispose { auth.removeAuthStateListener(l) }
    }
    return loggedIn
}
