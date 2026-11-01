# Samsung Galaxy S24 Sztereó 3D Fényképező Projekt

Ez a projekt kifejezetten a **Samsung Galaxy S24** telefon hardverére készült, hogy az 1× Főkamerával és a 3× Telekamerával szimultán (egyidejűleg) sztereó fotópárt rögzítsen vízszintes tartásban.

## Fő funkciók
1. **Kamera Hardverteszt (CameraTesterActivity)**: Felderíti a telefon összes fizikai lencséjét, kiolvassa a `CameraManager.getConcurrentCameraIds()` listát, és megvizsgálja a Fő- és Telekamera egyidejű megnyithatóságát.
2. **Sztereó Kamera (StereoCaptureActivity)**: Vízszintes nézetben egyetlen exponálással készíti el mindkét képet.
3. **Automatikus Crop Match**: A 24 mm főkamera képéből 2.79×-es kivágást készít, így a téma mérete pixelre pontosan egyezik a 67 mm-es telekamera képével.
4. **Fájlmentés**: `IMG_L.jpg` és `IMG_R.jpg` mentése a telefon `DCIM/Stereo3D` mappájába.

## Hogyan kaphatok közvetlen APK-t?

### 1. Módszer: Ingyenes GitHub Actions Felhő-fordító (AJÁNLOTT)
A projekt tartalmaz egy előre konfigurált `.github/workflows/build-apk.yml` fájlt.
1. Töltse fel a kibontott projektet egy ingyenes GitHub fiókba.
2. A GitHub 2 perc alatt a felhőben lefordítja az `app-debug.apk` fájlt.
3. Közvetlenül letöltheti a telefonjára az Actions fül alól!

### 2. Módszer: Android Studio-ban
1. Töltse le a ZIP-et erről az oldalról.
2. Csomagolja ki, és nyissa meg Android Studio-ban (`File -> Open`).
3. Csatlakoztassa a Samsung S24-et USB-n (USB hibakereséssel), és kattintson a zöld **Run ▶** gombra, vagy válassza a **Build -> Build APK** menüpontot!
