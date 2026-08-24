# Instruksi Pemeliharaan VoidEdit

## ATURAN WAJIB — JANGAN DIABAIKAN

- SEBELUM MENGERJAKAN BUG ATAU FITUR APA PUN, WAJIB BACA `TROUBLESHOOTING.md` TERLEBIH DAHULU.
- JIKA GEJALA, ERROR TOOLING, ATAU SOLUSINYA SUDAH TERCATAT, DILARANG MENGULANG ANALISIS DAN PERCOBAAN YANG SAMA. LANGSUNG PAKAI PROSEDUR YANG SUDAH TERBUKTI.
- KHUSUS ERROR `bwrap: fchdir to oldroot`, SETELAH SATU KEGAGALAN LANGSUNG GUNAKAN REWRITE MEKANIS TERKONTROL, LALU SATU RANGKAIAN VALIDASI. JANGAN MENGULANG `apply_patch`.
- JIKA PENGGUNA SUDAH MEMBERI IZIN PUSH DAN MEMBUAT PR, SELESAIKAN IMPLEMENTASI, SATU RANGKAIAN VALIDASI, COMMIT, PUSH, DAN PR TANPA MEMINTA IZIN ULANG.
- USAHAKAN SATU COMMIT UNTUK SATU TASK; JANGAN MEMBUAT COMMIT DOKUMENTASI TERPISAH JIKA CATATAN BISA DISERTAKAN DALAM COMMIT UTAMA.

- Baca `TROUBLESHOOTING.md` sebelum mendiagnosis bug agar masalah lama tidak dianalisis ulang dari nol.
- Setelah solusi terkonfirmasi, tambahkan gejala, penyebab, perubahan, verifikasi, dan referensi commit/PR ke `TROUBLESHOOTING.md`.
- Jangan mencatat password, private key, isi kredensial, keystore, atau fingerprint host aktual.
- Jangan memasukkan screenshot debugging ke commit kecuali pengguna memintanya secara eksplisit.
- Repo tidak menyertakan Gradle wrapper. Build APK dilakukan oleh GitHub Actions yang membuat wrapper Gradle 8.7.
- Workflow build hanya terpicu setelah perubahan masuk ke `main`/`master` atau tag `v*`; push feature branch saja tidak menjalankan build.
- Di environment Codex ini, `apply_patch` pernah gagal dengan `bwrap: fchdir to oldroot`. Coba sekali; jika error yang sama muncul, langsung gunakan rewrite mekanis terkontrol, periksa diff, lalu jalankan `git diff --check`.
- Untuk publikasi GitHub: periksa PR existing sebelum membuat PR baru. Push branch tidak otomatis membuat Pull Request.
