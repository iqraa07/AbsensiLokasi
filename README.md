# AbsensiLokasi (Android) — Absensi Berbasis Lokasi (Radius Kantor)

AbsensiLokasi adalah aplikasi Android untuk **absensi masuk dan pulang berbasis lokasi**.  
Aplikasi memvalidasi apakah pengguna berada **dalam radius kantor** sebelum data absensi disimpan ke **Firebase Authentication** dan **Cloud Firestore**. Riwayat absensi juga bisa dilihat langsung dari aplikasi.

> Catatan: Fitur **Register** sengaja **tidak digunakan** untuk kebutuhan tugas. Aplikasi hanya menyediakan **Login** (akun dibuat dari Firebase Console).

---

## Fitur Utama

- **Login** menggunakan Email/Password (Firebase Authentication)
- Mengambil **lokasi realtime** dari perangkat (Google Play Services Location)
- **Validasi radius kantor** (default ±100 meter) sebelum absensi tersimpan
- **Deteksi lokasi palsu (mock location)** untuk mengurangi kecurangan
- Menyimpan data absensi ke **Firestore** (IN/OUT, waktu, jarak, lokasi, dll)
- **Aturan absensi IN/OUT** agar urutan masuk–pulang tetap konsisten
- **Auto Absen (opsional)**: bisa otomatis melakukan IN/OUT saat berada di dalam radius sesuai status terakhir
- Tombol cepat untuk membuka **Maps Kantor** dan **Maps Saya**
- **Profil karyawan** (nama bisa diubah dan tersimpan di database)
- **Tema aplikasi**: Light / Dark / System
- Penyimpanan preferensi (tema & auto absen) menggunakan **DataStore**

---

## Tech Stack

- Kotlin  
- Jetpack Compose (Material 3)  
- Firebase Authentication (Email/Password)  
- Cloud Firestore  
- Google Play Services Location  
- AndroidX DataStore Preferences  
- Kotlin Coroutines  

---

## Struktur Project

- `app/src/main/java/com/iqra/absensi/MainActivity.kt`  
  Berisi UI (Compose) dan logika utama: login, lokasi, absensi, dan riwayat.
- `app/src/main/AndroidManifest.xml`  
  Permission lokasi (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)
- `app/google-services.json`  
  Konfigurasi Firebase

---

## Skema Data (Firestore)

### 1) Profil Karyawan
Path: `companies/{companyId}/employees/{uid}`

Field contoh:
- `uid` (String)
- `name` (String)
- `email` (String)
- `createdAt` (Timestamp)
- `updatedAt` (Timestamp)

Aplikasi akan membuat profil otomatis setelah login jika belum ada.

### 2) Data Absensi
Path: `companies/{companyId}/attendance/{docId}`

Field contoh:
- `employeeId` (String)
- `type` (String): `IN` / `OUT`
- `dateKey` (String): `yyyy-MM-dd`
- `lat` (Double), `lng` (Double)
- `accuracy` (Float)
- `distance` (Double) → jarak ke titik kantor (meter)
- `address` (String) → alamat hasil geocoding (opsional)
- `device` (String)
- `ts` (Timestamp)

---

## Konfigurasi Lokasi Kantor

Konfigurasi utama ada di `MainActivity.kt`:

- `OFFICE_LAT` / `OFFICE_LNG` → koordinat kantor  
- `OFFICE_RADIUS_M` → radius kantor (meter)  
- `companyId` → id perusahaan (default: `cmpA`)  

Silakan sesuaikan sesuai titik lokasi yang digunakan.

---

## Requirement

- Android Studio
- JDK 11
- Min SDK 24
- Device/emulator yang mendukung Google Play Services
- Firebase Project (Auth + Firestore)

---

## Cara Menjalankan

1. Clone repository
2. Buka project di Android Studio
3. Siapkan Firebase:
   - Aktifkan **Authentication → Email/Password**
   - Buat **Cloud Firestore**
4. Pastikan `google-services.json` berada di `app/`
5. Sync Gradle, lalu Run

---

## Cara Membuat Akun (Karena Register Tidak Dipakai)

1. Masuk Firebase Console → **Authentication**
2. Buka tab **Users** → **Add user**
3. Isi email & password
4. Login di aplikasi menggunakan akun tersebut

---

## Permission yang Digunakan

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

Permission diminta saat aplikasi mengambil lokasi untuk proses absensi.

---

## Mode Firestore Emulator (Opsional)

Jika ingin memakai Firestore Emulator, atur pada `MainActivity.kt`:
- `USE_EMULATOR` (default: `false`)
- `EMULATOR_HOST`
- `EMULATOR_PORT`

---
