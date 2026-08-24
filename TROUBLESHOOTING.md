# Catatan Troubleshooting VoidEdit

Catatan ini merangkum masalah dan solusi yang sudah ditemukan selama pengembangan. Jangan menyimpan rahasia atau nilai fingerprint aktual di sini.

## Gambaran codebase

- Aplikasi Android native membungkus editor HTML/CSS/JavaScript di WebView.
- `MainActivity.kt` menangani WebView, Android Storage Access Framework (SAF), bridge JavaScript, penyimpanan preferensi, dan koordinasi SFTP.
- `SftpManager.kt` menangani koneksi serta operasi file SFTP melalui SSHJ.
- `SftpConnectionStore.kt` menyimpan koneksi terenkripsi.
- `LocalFolderBookmarkStore.kt` menyimpan bookmark folder SAF.
- `voidedit.html`, `voidedit.css`, dan `voidedit.js` menangani UI serta logika editor/explorer.

## 2026-08-20 — Semua aksi `+` gagal di root folder lokal

### Gejala

- Listing bookmark folder Termux berhasil.
- `File baru`, `Folder baru`, `Upload file`, dan `Import ZIP` gagal dengan pesan `Invalid URI`.
- Masalah terjadi ketika aksi dijalankan pada root bookmark lokal.

### Penyebab

`ACTION_OPEN_DOCUMENT_TREE` memberikan URI berbentuk tree URI. Listing dapat memakai tree URI, tetapi operasi tulis `DocumentsContract.createDocument()` membutuhkan parent document URI. URI subfolder sudah berbentuk document URI sehingga bug terutama terlihat di root bookmark.

### Solusi

- Tambahkan `writableDocumentUri()` di `MainActivity.kt`.
- Pertahankan document URI yang sudah valid.
- Konversi tree URI memakai `DocumentsContract.buildDocumentUriUsingTree()`.
- Terapkan normalisasi pada create file/folder, upload file, dan import ZIP rekursif.

### Verifikasi dan referensi

- `git diff --check` lolos.
- Commit: `074a0d0 Fix local SAF write actions`.
- PR: `#5 Fix local SAF write actions`, sudah di-merge ke `main`.

## 2026-08-20 — Folder lokal Termux tidak dapat dihapus

### Gejala

Penghapusan folder menghasilkan pesan seperti `Failed to delete document with id ...`.

### Penyebab

VoidEdit hanya memanggil `DocumentsContract.deleteDocument()` pada folder induk. DocumentsProvider Termux memakai penghapusan non-rekursif, sehingga folder yang masih berisi file, subfolder, atau file tersembunyi gagal dihapus. Dialog UI sebelumnya sudah menjanjikan penghapusan beserta seluruh isi, tetapi implementasi native belum sesuai.

### Solusi

- Tambahkan `deleteDocumentRecursively()` di `MainActivity.kt`.
- Query semua child tanpa mengikuti filter tampilan hidden.
- Hapus anak dari level terdalam, lalu hapus folder induk.
- Bersihkan `currentFileUri` jika file aktif ikut terhapus.
- Pertahankan penghapusan rekursif SFTP dan ganti `stat()` menjadi `lstat()` agar symlink ke direktori tidak diikuti.

### Verifikasi dan referensi

- Syntax dan `git diff --check` lolos.
- Commit: `d6fdc36 Fix recursive folder deletion`.
- PR: `#6 Fix recursive folder deletion`, sudah di-merge ke `main`.

## 2026-08-24 — Koneksi SFTP ditolak karena fingerprint lama

### Gejala

Koneksi ke host yang server key-nya berubah ditolak dengan pesan `Fingerprint host berubah. Koneksi ditolak.`.

### Penyebab

- Fingerprint dipercaya disimpan di SharedPreferences dengan key berbasis `host:port`.
- Fitur hapus koneksi sebelumnya hanya menghapus kredensial dari `SftpConnectionStore` dan tidak menghapus fingerprint terkait.
- Kontrol hapus sudah ada di menu long-press, tetapi sulit ditemukan.

### Solusi

- Tambahkan tombol merah `Hapus koneksi` pada form Edit koneksi.
- Gunakan satu alur konfirmasi untuk penghapusan dari form Edit maupun menu long-press.
- Saat koneksi dihapus, ambil data host/port terlebih dahulu, hapus koneksi terenkripsi, lalu hapus preference fingerprint terkait.
- Setelah dihapus, koneksi berikutnya wajib melalui verifikasi fingerprint baru.

### Verifikasi dan referensi

- `node --check app/src/main/assets/voidedit.js` lolos.
- `git diff --check` lolos.
- Commit: `c557c7a Add SFTP connection deletion`.
- Branch remote: `feat/delete-sftp-connection`.
- PR: `#7 Add SFTP connection deletion`, sudah di-merge ke `main`.

## 2026-08-24 — Download SFTP single file, folder, dan multi-select

### Kebutuhan

- Satu file remote dapat disimpan langsung melalui pemilih lokasi Android.
- Satu folder remote otomatis diunduh sebagai ZIP.
- Beberapa file/folder dapat dipilih sekaligus dan otomatis digabung menjadi ZIP.

### Implementasi

- Tambahkan mode pilihan pada daftar SFTP: pilih item dari menu long-press, lalu tap item lain untuk menambah atau mengurangi pilihan.
- Tombol Back membatalkan mode pilihan lebih dahulu dan tombol tambah disembunyikan selama pilihan aktif.
- Tambahkan bridge `sftpDownload` dan launcher `ACTION_CREATE_DOCUMENT` agar pengguna menentukan nama serta lokasi hasil.
- Single file dialirkan langsung dari SSHJ ke SAF tanpa memuat seluruh file ke memori.
- Folder dan multi-item ditraversal sambil langsung dialirkan ke `ZipOutputStream`; folder kosong dan file tersembunyi tetap disertakan, symlink tidak diikuti sebagai direktori.
- Batasi pilihan awal ke 500 item dan kedalaman folder ke 256 level; jumlah entry hasil traversal tidak dibatasi di memori.
- Nonaktifkan retry otomatis untuk operasi streaming agar kegagalan koneksi di tengah proses tidak menulis ulang data ke output yang sama.
- Gunakan koneksi SFTP khusus untuk download agar transfer besar tidak memblokir listing dan aksi explorer.
- Bersihkan nama entry ZIP dari slash, backslash, karakter kontrol, `.` dan `..`.

### Verifikasi

- `node --check app/src/main/assets/voidedit.js` lolos.
- `git diff --check` lolos.
- Build lokal tidak tersedia karena repo tidak membawa wrapper; kompilasi Android diverifikasi oleh GitHub Actions setelah perubahan masuk ke branch yang memicu workflow.
- Commit implementasi: `e2f4b21 Add SFTP download selection`.
- PR: `#8 Add SFTP file and folder downloads`.

## 2026-08-24 — Download folder besar diam lalu explorer SFTP macet

### Gejala

- Download folder project besar tidak menampilkan progress selama beberapa menit.
- Selama proses itu listing explorer SFTP hanya menampilkan loading.
- Operasi akhirnya gagal karena jumlah item melewati batas maksimum traversal.

### Penyebab

- Implementasi awal mengumpulkan seluruh tree remote ke list di memori sebelum satu byte ZIP ditulis.
- Traversal menjalankan `lstat` tambahan untuk setiap child dan berhenti pada batas 20.000 entry.
- Download memakai koneksi serta lock SFTP yang sama dengan explorer, sehingga request listing menunggu transfer selesai.

### Solusi

- Tulis entry ZIP langsung saat traversal depth-first; tidak lagi mengumpulkan seluruh tree atau membatasi jumlah entry di memori.
- Gunakan attribute dari hasil `ls` untuk menghindari round-trip `lstat` per child.
- Jalankan download lewat koneksi SFTP terpisah dengan config terautentikasi yang sama, sehingga explorer tetap responsif.
- Kirim progress sejak entry pertama dengan total dinamis dan throttle sekitar empat update per detik agar WebView tidak dibanjiri event.
- Pertahankan batas kedalaman 256 level sebagai perlindungan terhadap tree abnormal.

### Verifikasi dan referensi

- `node --check app/src/main/assets/voidedit.js` lolos.
- `git diff --check` lolos.
- PR: `#8 Add SFTP file and folder downloads`.

## 2026-08-24 — Download dari bookmark folder lokal

### Kebutuhan

- Item di folder yang ditambahkan lewat `Tambah jalur` dapat di-download ke lokasi SAF lain.
- Satu file disalin langsung, sedangkan satu folder atau multi-item otomatis menjadi ZIP.
- Root bookmark juga dapat di-download langsung dari menu long-press bookmark.

### Implementasi

- Tambahkan mode multi-select, progress, serta aksi download pada explorer lokal.
- Gunakan `ACTION_CREATE_DOCUMENT` agar pengguna memilih nama dan lokasi output.
- Stream file langsung dari `ContentResolver` ke output tanpa menyalin seluruh file ke cache.
- Traversal folder dilakukan depth-first sambil menulis `ZipOutputStream`; file tersembunyi tetap disertakan dan jumlah entry tidak dikumpulkan di memori.
- Batasi pilihan awal ke 500 item dan kedalaman folder ke 256 level.
- Bersihkan nama entry ZIP dari slash, backslash, karakter kontrol, `.` dan `..`.
- Tombol Back membatalkan multi-select lokal sebelum melakukan navigasi folder.

### Verifikasi dan referensi

- `node --check app/src/main/assets/voidedit.js` lolos.
- `git diff --check` lolos.
- PR: `#8 Add SFTP file and folder downloads`.

## Build dan GitHub Actions

- Repo tidak memiliki `gradlew`/Gradle wrapper dan environment lokal tidak memiliki Gradle.
- `.github/workflows/build.yaml` mengunduh Gradle 8.7, membuat wrapper, lalu menjalankan `assembleDebug`.
- Build release hanya berjalan jika secret keystore tersedia.
- Trigger push hanya untuk `main`, `master`, dan tag `v*`; feature branch tidak otomatis menjalankan build.
- Push branch dan pembuatan Pull Request adalah dua aksi berbeda. Gunakan `gh pr list` untuk mengecek duplikat sebelum `gh pr create`.

## Kendala tooling Codex di workspace

### Gejala

`apply_patch` gagal membaca file dengan error `bwrap: fchdir to oldroot: No such file or directory`, termasuk saat target dipindah ke `/tmp`.

### Prosedur yang terbukti berhasil

1. Jangan mengulang `apply_patch` berkali-kali setelah error yang sama terkonfirmasi.
2. Gunakan rewrite mekanis yang sangat terarah, misalnya `perl -0pi`, hanya pada potongan yang sudah diperiksa.
3. Tinjau seluruh `git diff` setelah rewrite.
4. Jalankan `git diff --check`.
5. Untuk JavaScript, jalankan `node --check`.
6. Stage file dengan path eksplisit; jangan gunakan `git add .` atau `git add -A`.
7. Jangan stage screenshot debugging yang muncul sebagai file untracked.

## Riwayat publikasi

- Repo awal di-clone dari `https://github.com/attaul-fullstack-dev/Void-editor.git` pada branch `main`.
- PR #5 memperbaiki seluruh aksi tulis SAF di root bookmark dan sudah di-merge.
- PR #6 memperbaiki recursive folder deletion dan sudah di-merge.
- PR #7 menambahkan fitur hapus koneksi serta fingerprint dari commit `c557c7a` dan sudah di-merge.
