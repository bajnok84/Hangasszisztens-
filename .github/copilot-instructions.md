# Copilot project instructions

- Mindig magyarul válaszolj.
- Teljes fejlesztési feladat előtt tekintsd át az érintett Kotlin-forrásokat, manifestet, erőforrásokat, meglévő teszteket és build-konfigurációt.
- Kis, célzott módosításokat végezz; őrizd meg a meglévő viselkedést, a felületi szövegek magyar nyelvét és a repository kódstílusát.
- Új vagy javított viselkedéshez adj hozzá vagy frissíts célzott teszteket.
- A `com.sajat.hangasszisztens` application ID-t és az alkalmazás aláírási beállításait ne módosítsd, kivéve, ha a felhasználó ezt kifejezetten kéri.
- API-kulcsot, személyes adatot vagy más titkot ne írj forráskódba, naplóba vagy CI-kimenetbe; kövesd a projekt biztonságos tárolási megoldásait.
- Mikrofon- és kamerahasználatnál tartsd be az Android engedélykéréseit, előtérszolgáltatási és háttérindítási korlátozásait. Háttérből ne indíts felhasználói interakciót igénylő rögzítést.
- Helyi ellenőrzéshez Java 21-et és a Gradle wrappert használd. Kódmódosítás után futtasd a `./gradlew testDebugUnitTest assembleDebug` parancsot; a sikertelen vagy megszakadt buildet ne jelentsd sikeresnek.
- Új függőséget, SDK-célszintet, Gradle- vagy Android Gradle Plugin-verziót csak a feladat indokoltsága esetén változtass.
- Szerepválasztás: implementáláshoz `android-developer`, teszteléshez és regresszió-ellenőrzéshez `android-test-engineer`, biztonsági kódellenőrzéshez `android-security-reviewer` szerepet használd.
