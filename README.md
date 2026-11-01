# Samsung Galaxy S24 Sztereó 3D Fényképező & Videófelvevő Projekt

Ez a projekt kifejezetten a **Samsung Galaxy S24** telefon hardverére készült, hogy az 1× Főkamerával és a 3× Telekamerával szimultán (egyidejűleg) sztereó fotópárt és sztereó videópárt rögzítsen vízszintes tartásban.

## Fő funkciók
1. **Kamera Hardverteszt (CameraTesterActivity)**: Felderíti a telefon összes fizikai lencséjét, kiolvassa a `CameraManager.getConcurrentCameraIds()` listát, és megvizsgálja a Fő- és Telekamera egyidejű megnyithatóságát a 'B' módszerrel (Logical Multi-Camera fizikai streamelés).
2. **Sztereó Fényképező (StereoCaptureActivity)**: Vízszintes nézetben egyetlen exponálással készíti el mindkét képet szinkronban.
   - **Automatikus Side-by-Side (SBS) összefűzés**: Automatikusan generál egy közös `IMG_YYYYMMDD_HHMMSS_SBS.jpg` 3D fotót VR szemüvegekhez és 3D kijelzőkhöz, miközben a különálló `IMG_L.jpg` és `IMG_R.jpg` fájlokat is elmenti a `DCIM/Stereo3D` albumba.
   - **Garantált felületi stabilitás**: Megszünteti a Camera2 „Surface abandoned” hibát párhuzamos felület-életciklus kezeléssel és rögzített felbontású bufferekkel.
3. **Sztereó Videófelvevő (StereoVideoActivity)**: 
   - **Hardveres Képméret-Egyeztetés (ISP Scaler Crop)**: A főkamera videofolyamára a Samsung ISP hardveres 2.79×-es kivágást (`CaptureRequest.SCALER_CROP_REGION`) alkalmaz valós időben 0% processzorterheléssel, így a videón a bal és jobb szem témamérete PIXELRE PONTOSAN megegyezik a 3× telekamerával!
   - **Szinkronizált Dupla MP4**: `VID_L.mp4` (bal szem + hang) és `VID_R.mp4` (jobb szem) a `Movies/Stereo3D` albumba kerül rögzítésre.

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
