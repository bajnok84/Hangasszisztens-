# Copilot project instructions

- Mindig magyarul válaszolj.
- Teljes fejlesztési feladatoknál először tekintsd át a projekt releváns részeit, meglévő tesztjeit és build-konfigurációját.
- A javításokat a meglévő viselkedés és a repository kódstílusának megőrzésével végezd.
- Új vagy javított viselkedéshez adj hozzá vagy frissíts célzott teszteket.
- A `com.sajat.hangasszisztens` application ID-t és az alkalmazás aláírási beállításait ne módosítsd, kivéve, ha a felhasználó ezt kifejezetten kéri.
- Kódmódosítás után futtasd a `./gradlew testDebugUnitTest assembleDebug` parancsot, és a hibákat javítsd a készre jelentés előtt.
- Helyi ellenőrzéshez Java 21-et használj; ezt a build és a CI környezet is követi.
