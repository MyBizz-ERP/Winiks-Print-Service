# WINIKS POS Print (Android Native Thermal Print Service)

Production-ready Android Native Print Service for 58mm (2-inch) and 80mm (3-inch) Bluetooth Thermal Receipt Printers.

## Key Features
- **Android PrintService Architecture:** Connects directly with Android's system PrintSpooler. Works with Chrome, Edge, ERPs, and all Android apps via `window.print()`.
- **Bluetooth POS Support:** Direct RFCOMM (SPP UUID `00001101-0000-1000-8000-00805F9B34FB`) with multi-tier connection fallback.
- **Hardware Flow Control:** 512-byte buffered streaming to prevent thermal printer memory overflow.
- **High-Fidelity Rasterization:** 203 DPI monochrome ESC/POS raster bit-image conversion for crystal clear Marathi and English typography, tables, and QR codes.
- **OEM Stability:** Native settings activity with full Android lifecycle compliance — zero bounces and zero crashes on Vivo Funtouch OS, Xiaomi MIUI, Oppo ColorOS, and Samsung OneUI.
- **1-Click DeepLink:** Supports `winiksprint://print` URI scheme for zero-dialog instant printing.

## Build Instructions
Built with Gradle 8.2 and Kotlin 1.9.22 targeting Android API 34.
```bash
./gradlew assembleDebug
```
