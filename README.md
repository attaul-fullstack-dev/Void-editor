# VoidEdit Android

APK wrapper dari VoidEdit HTML editor. Buka file kode langsung dari file manager via "Buka dengan".

---

## Deploy dari HP (tanpa PC)

### 1. Upload ke GitHub
Buat repo baru di github.com → upload semua file dari ZIP ini.

### 2. Generate Keystore via GitHub Actions
Buat file baru di repo: `.github/workflows/gen-keystore.yaml` (isinya ada di file `gen-keystore.yaml` yang terpisah — password TIDAK ditulis di dalam file ini, kamu isi sendiri saat menjalankan workflow).

Jalankan lewat tab **Actions → Generate Keystore → Run workflow**, lalu isi:
- `keystore_password`: password buatanmu sendiri, jangan pakai contoh dari internet manapun
- `key_alias`: bebas, contoh `voidedit`

Setelah selesai → download artifact `keystore-b64` → copy isi file `keystore.b64`.

### 3. Tambah GitHub Secrets
Settings repo → Secrets → Actions → New repository secret:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Paste isi `keystore.b64` |
| `KEYSTORE_PASSWORD` | password yang kamu isi di step 2 |
| `KEY_ALIAS` | alias yang kamu isi di step 2 |
| `KEY_PASSWORD` | sama dengan `KEYSTORE_PASSWORD` |

> ⚠️ Jangan pernah tulis password asli di README atau file yang ikut ter-commit. Simpan hanya di GitHub Secrets atau password manager (mis. Bitwarden).

### 4. Build
Push ke main → tab Actions → tunggu selesai → download APK dari Artifacts.

### 5. Buat Release
Di GitHub: Releases → Create release → tag `v1.0.0` → publish.
Actions otomatis attach APK ke release.
