# Aplikasi Petugas PLN — Pencatatan Pemasangan (Native Android)

Aplikasi **native Android** (Kotlin + Jetpack Compose) untuk petugas lapangan
mencatat data pemasangan: data pelanggan, lokasi (koordinat GPS/peta),
tanggal pemasangan, dan foto pemasangan (minimal 2).

> Ini adalah project Android murni (bukan PWA/TWA). Build langsung
> menghasilkan file APK.

## Fitur

- **Menu utama** dengan profil petugas — nama petugas bisa diedit dan
  otomatis menjadi default pada form berikutnya
- **Input data pemasangan**: ID pelanggan, nama pelanggan, alamat
- **Koordinat lokasi** via GPS (FusedLocationProvider) atau tekan-lama pada
  peta OpenStreetMap (osmdroid, tanpa API key), marker bisa digeser
- **Tanggal pemasangan** dengan Material DatePicker (format Indonesia)
- **Foto pemasangan minimal 2** (kamera, maksimal 4), disimpan via FileProvider
- **Riwayat data** + pencarian, detail, edit, hapus, dan bagikan (share)
- Database lokal **Room** — berjalan tanpa server

## Cara Build (Android Studio)

1. Install **Android Studio** (Ladybug 2024.2.1 atau lebih baru).
2. **File > Open** → pilih folder project ini (`AplikasiPetugas`).
3. Tunggu **Gradle Sync** selesai (butuh koneksi internet, unduhan ±150 MB).
4. Jalankan aplikasi: sambungkan HP (aktifkan *USB Debugging*) atau buat
   emulator, lalu klik **Run** (Shift+F10).
5. Build APK:
   **Build > Build App Bundle(s) / APK(s) > Build APK(s)**
   - Hasil: `app/build/outputs/apk/debug/app-debug.apk`

> Atau lewat terminal: `./gradlew assembleDebug`

## Konfigurasi

| Item | Nilai |
| --- | --- |
| applicationId | `com.petugas.pemasangan` |
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 35 |
| Bahasa UI | Indonesia |
| Peta | OpenStreetMap (osmdroid) — perlu internet untuk memuat tile |

## Izin yang Digunakan

- `CAMERA` — mengambil foto pemasangan
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — mengambil koordinat
- `INTERNET` — memuat peta OpenStreetMap

## Struktur Kode

```
app/src/main/java/com/petugas/pemasangan/
├── MainActivity.kt          # Single Activity (Compose)
├── PetugasApp.kt            # Konfigurasi osmdroid
├── data/
│   ├── db/                  # Room: Entity, DAO, Database
│   ├── InstallationRepository.kt
│   └── SettingsRepository.kt # DataStore: nama petugas
├── util/                    # Format tanggal, codec foto (JSON)
└── ui/
    ├── AppNavHost.kt        # Navigasi antar layar
    ├── menu/                # Menu utama + edit nama petugas
    ├── form/                # Form input + MapPicker + ViewModel
    ├── list/                # Riwayat + pencarian
    └── detail/              # Detail + share + hapus
```

## Catatan

- Foto disimpan di penyimpanan khusus aplikasi
  (`Android/data/com.petugas.pemasangan/files/Pictures/pemasangan`).
- Nama petugas disimpan via DataStore; setiap kali data disimpan, nama
  petugas pada form akan tersimpan sebagai default berikutnya.
- Jika ingin peta Google Maps di dalam aplikasi, tambahkan
  Google Maps SDK + API key (opsional).
