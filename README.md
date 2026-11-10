# WINIKS Print Service (Official Android Native APK)

The official, ultra-lightweight, 100% free hardware print service for **WINIKS Enterprise Wholesale & Retail ERP**.
Connects any Android phone directly to Bluetooth 80mm (3") & 58mm (2") Thermal POS Printers without third-party app trial limits, ads, or watermarks.

---

## 🚀 Key Features

* **Universal Bluetooth ESC/POS Support:** Works seamlessly with all 80mm/58mm thermal printers (Everycom, NGX, TVSE, Chinese portable BT printers).
* **High-Fidelity Monochrome Rasterization:** Renders Marathi and English Devanagari text, bold fonts, GST tables, and QR codes at native 203 DPI (`GS v 0` format).
* **Hardware Controls:**
  * Auto Cut paper at end of job (`GS V 0` Full Cut / `GS V 1` Partial Cut).
  * Auto feed blank lines (1–6 lines) before cut to protect QR codes & due balances.
  * Cash Drawer Kick (`ESC p 0 25 250`) on checkout.
  * Width Scaling (80% to 110%) for edge-clipping prevention.
* **Auto-Reconnect & Background Service:** Hooks natively into Android's `PrintService` architecture. Once enabled in Android Settings, prints are caught silently in the background and transmitted to the printer in <1 second.
* **100% Free & Unlimited:** No 26-print trials, no subscriptions, no watermarks.

---

## 🛠️ Project Structure

```
017-Winiks-Print-Service/
├── app/
│   ├── src/main/
│   │   ├── java/com/winiks/printservice/
│   │   │   ├── MainActivity.kt          # Settings UI & Bluetooth Discovery
│   │   │   ├── WiniksPrintService.kt    # Android Native PrintService Driver
│   │   │   ├── BluetoothPrintManager.kt # SPP RFCOMM Bluetooth Transmitter
│   │   │   └── EscPosHelper.kt          # Monochrome 1-bit ESC/POS Bitmap Encoder
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml # Clean Dark Indigo Settings Layout
│   │   │   ├── values/                  # Strings, Themes, Brand Colors
│   │   │   └── xml/printservice.xml     # Android PrintService Manifest Descriptor
│   │   └── AndroidManifest.xml          # Modern Bluetooth Permissions (S+)
│   └── build.gradle
├── .github/workflows/
│   └── build-apk.yml                    # Automated GitHub Cloud APK Builder
├── gradle/wrapper/
├── build.gradle
└── settings.gradle
```

---

## 📦 How to Build the APK

### Option A: Via GitHub Actions (Cloud Build - Zero local setup)
1. Push this folder to a GitHub repository (e.g. `MyBizz-ERP/Winiks-Print-Service`).
2. GitHub Actions will automatically run the `.github/workflows/build-apk.yml` workflow.
3. Download the generated `WINIKS-Print-Service-APK` directly from the GitHub Actions Artifacts tab.

### Option B: Local Gradle Build
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 📱 How to Use on Client's Phone (1-Minute Setup)

1. **Install APK:** Install `WINIKS-Print-Service.apk` on the phone.
2. **Open App:** Tap **"Set Preferred Printer"** and select the paired Bluetooth printer.
3. **Turn On Service:** Tap **"Enable in Android Settings"** (or go to `Settings -> Printing -> WINIKS Print Service`) and turn the switch **ON**.
4. **Done:** From that moment forward, any bill printed from **WINIKS ERP** will automatically print over Bluetooth with auto-cut in under 1 second!

