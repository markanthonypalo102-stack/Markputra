# Mputra — gabay sa pag-upload sa GitHub (Tagalog)

## Download at extract

1. I-download ang `Mputra_COMPLETE_PROJECT_NO_SCIPY.zip`.
2. Sa Windows, i-right-click ang ZIP at piliin ang **Extract All**.
3. Buksan ang extracted na `Mputra` folder. I-enable ang **View → Show → Hidden items** para makita ang `.github` folder.

## Pinakamadaling paraan (recommended): GitHub Desktop

1. Gumawa ng Public GitHub repository na `Mputra`. Iwanang unchecked ang Add README, `.gitignore`, at license para walang conflict.
2. Sa GitHub Desktop, piliin ang **File → Clone repository → URL** at ilagay ang URL ng bagong repository mo.
3. Sa GitHub Desktop, piliin ang **Repository → Show in Explorer**.
4. Kopyahin ang *lahat ng laman* ng extracted `Mputra` folder (hindi ang ZIP at hindi outer folder lang) papunta sa cloned repository folder. Pagsamahin ang folders at i-overwrite ang same-named files kung tinatanong.
5. Sa GitHub Desktop, commit sa `main` at pindutin ang **Push origin**.

## Kung GitHub website lang ang gagamitin mo

Ang GitHub website ay hindi nag-e-extract ng ZIP. Kailangan munang i-extract ito sa Windows. I-upload ang `app` at `tests` folders at ang root files sa **Add file → Upload files**. Kung hindi pinapayagan ang drag-and-drop ng hidden `.github` folder, gumawa ng file sa **Add file → Create new file** na may eksaktong filename `.github/workflows/build-android.yml`, at i-paste ang contents ng katumbas na file mula sa extracted ZIP. Huwag gumawa ng `workflows/build-android.yml` lang sa root; ang buong path ay kailangang `.github/workflows/build-android.yml`.

## Build at APK

1. Buksan ang **Actions → Build Mputra Android APK** pagkatapos ng push/commit.
2. Kung may red X, buksan ang unang failed step at tingnan ang buong error. Huwag ituring na handa ang APK kapag failed ang build.
3. Kapag green check, buksan ang **Releases** at i-download ang `Mputra.apk` (o ang artifact na nakalista sa run).
4. I-install lamang sa compatible na Android device at aprubahan ang Android screen-capture at overlay permissions kapag tinanong.

## Mga limitasyong mahalaga

- Ang ZIP ay source code; hindi pa ito prebuilt APK.
- Naayos ang dating SciPy dependency error sa Android module. Gayunman, hindi pa mapapatunayang walang anumang build/runtime error hanggang pumasa ang GitHub Actions build at masubukan sa tunay na phone.
- Ang password ay nasa source bilang local access gate, kaya hindi ito server-grade security.
- Ang score ay directional evidence, hindi garantisadong win probability. Ang order execution ay nananatiling manual.
