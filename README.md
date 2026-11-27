<p align="center"><img src="assets/logo/rosenweg-uhr-512.png" width="96" alt="Logo"></p>
<h1 align="center">Rosenweg Uhr</h1>
<p align="center">Uhr, Wecker, Stoppuhr und Timer für die Rosenweg-Smartphones.</p>
<p align="center"><a href="https://www.rosenweg4303.ch/apps">Herunterladen</a> · <a href="docs/anleitung.md">Anleitung</a></p>

## Inhalt

- [Überblick](#überblick)
- [Plattformen](#plattformen)
- [Anmeldung](#anmeldung)
- [Abgleich mit dem Rosenweg](#abgleich-mit-dem-rosenweg)
- [Rechte und Gruppen](#rechte-und-gruppen)
- [Bedienung](#bedienung)
- [Geteilte Rosenweg-Telefone](#geteilte-rosenweg-telefone)
- [Installation und Updates](#installation-und-updates)
- [Aufbau](#aufbau)
- [Bauen](#bauen)
- [CI und Release](#ci-und-release)
- [Konfiguration](#konfiguration)
- [Zweige](#zweige)
- [Herkunft und Lizenz](#herkunft-und-lizenz)

## Überblick

Uhr, Wecker, Stoppuhr und Timer für die Smartphones der Siedlung Rosenweg in Kaiseraugst. Funktional ist es die Uhr von Fossify; angepasst sind Name, Paketname, das Rosenweg-Logo und das Verhalten auf den geteilten Rosenweg-Telefonen. Das Uhr-Widget gehört zur Standard-Startseite von Rosenweg Launcher.

Was die App kann:

- **Uhr:** Zeit, weitere Zeitzonen.
- **Wecker:** mehrere Wecker, wiederholend, mit eigenem Ton.
- **Stoppuhr und Timer:** mit Runden bzw. mehreren Timern.
- **Uhr-Widget:** auf der Standard-Startseite von Rosenweg Launcher.
- **Auf allen Geräten gleich:** Wecker, Weltuhren und Timer gehören der Person und werden mit dem Rosenweg abgeglichen – auf dem eigenen Handy und an jedem geteilten Telefon, an dem sie sich anmeldet.
- **Einstellungen pro Person:** auf geteilten Telefonen gehören sie der angemeldeten Person, auch die Widget-Farben.
- **Rosenweg Updater:** hält auf dem eigenen Handy alle Rosenweg-Apps aktuell.

## Plattformen

| Plattform | Stand |
|---|---|
| Android | ja, APK auf [rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) |
| Geteilte Rosenweg-Telefone | ja, über den App-Katalog auf /smartphones |
| iOS, Desktop | nein (Android-App aus dem Fossify-Projekt) |

Nicht im Play Store und nicht auf F-Droid.

## Anmeldung

Seit 1.7.0 meldet sich die Uhr an, weil sie Daten mit dem Rosenweg abgleicht.

- **Eigenes Handy:** Beim Öffnen kommt ohne Anmeldung der gemeinsame Anmeldeschirm der Rosenweg-Apps (commons-rosenweg `cloud`). Man hält die Rosenweg-Karte ans Handy, meldet sich mit dem Rosenweg-Konto an oder scannt einen QR-Code von rosenweg4303.ch/smartphones. „Zurück“ schliesst die App. Die Anmeldung gilt nur für die Uhr. Gestellte Wecker klingeln auch ohne Anmeldung.
- **Geteilte Telefone:** Es gibt keinen eigenen Anmeldeschirm. Es gilt die Person, die am Sperrbildschirm angemeldet ist (Rosenweg Gerät).
- **Stand:** Menü → Einstellungen → Rosenweg → „Rosenweg-Konto und Abgleich“. Die Seite zeigt, wer angemeldet ist und wie, und wann zuletzt abgeglichen wurde. Dort gibt es „Jetzt abgleichen“ und, auf dem eigenen Handy, „Abmelden“. Nach dem Abmelden bleiben die Wecker auf dem Handy, werden aber nicht mehr abgeglichen.

## Abgleich mit dem Rosenweg

| Was | abgeglichen |
|---|---|
| Wecker | Zeit, Wochentage, Bezeichnung, Ton, Vibration, an/aus. Ein Wecker mit „Nur auf diesem Gerät“ (Schalter im Wecker-Dialog, Standard aus, nur auf dem eigenen Handy) bleibt lokal. War er schon abgeglichen, verschwindet er beim Umschalten von den anderen Geräten. Wecker, die ohne Anmeldung gestellt werden (z. B. von einer anderen App über „Wecker stellen“), gehören niemandem: Auf dem eigenen Handy bekommen sie „Nur auf diesem Gerät“, auf geteilten Telefonen werden sie bei der nächsten Anmeldung entfernt und klingeln vorher nicht. Gibt es den Ton auf dem anderen Gerät nicht, nimmt die Uhr den gleichnamigen Ton des Geräts, sonst den Standard-Weckton. |
| Weltuhren | gewählte Städte und eigene Namen |
| Timer | Dauer, Bezeichnung, Ton, Vibration. Ein laufender Timer läuft nur auf seinem Gerät. |
| Stoppuhr, Einstellungen | nein. Die Einstellungen gehen auf geteilten Telefonen weiterhin über die Telefonanlage, siehe unten. |

- **Wann:** beim Öffnen, rund 2 s nach jeder Änderung und stündlich im Hintergrund (WorkManager, wartet auf Netz). Ohne Netz geht alles weiter; die Änderungen gehen hoch, sobald wieder Netz da ist.
- **Konflikte:** Je Wecker, Weltuhr und Timer gilt die letzte Änderung. Gelöschtes bleibt als Grabstein stehen, damit es auf den anderen Geräten auch verschwindet.
- **Erstes Gerät, weitere Geräte:** Hat die Person schon Wecker beim Rosenweg, verschwinden auf einem neuen Gerät die zwei Beispiel-Wecker ab Werk (7:00 werktags, 9:00 am Wochenende), statt dazuzukommen. Sonst kommt alles dazu, was schon auf dem Gerät ist.
- **Backend:** Rosenweg-API `GET/PUT /api/me/app-daten/ch.rosenweg4303.clock` (Website `api/routes/app-daten.js`). Nur die Person selbst sieht ihre Daten; eine Technik-Ansicht gibt es nicht.

## Rechte und Gruppen

Die App hat keine eigenen Rollen. Wer sie auf den geteilten Telefonen bekommt, legt der App-Katalog der Telefonanlage fest.

| Wer | darf |
|---|---|
| Technik | legt die App im Katalog auf /smartphones an, wählt die Gruppen, schaltet sie aktiv; kann Apps auf den geteilten Telefonen ausblenden |
| Angemeldete Person in einer der Gruppen des Katalog-Eintrags (leer = alle) | sieht die App auf dem geteilten Telefon; Einstellungen gehören ihr |
| Alle | auf dem eigenen Handy: APK von /apps |

## Bedienung

| Was | So geht’s |
|---|---|
| Uhr, Wecker, Stoppuhr, Timer | Reiter unten |
| Wecker stellen | Reiter Wecker → Plus-Knopf |
| Weitere Zeitzone | Reiter Uhr → Plus-Knopf |
| Widget-Farben | Menü → Einstellungen bzw. beim Einrichten des Widgets |

Ausführlich: [docs/anleitung.md](docs/anleitung.md).

## Geteilte Rosenweg-Telefone

Auf den geteilten Telefonen (mit Rosenweg Gerät, Anmeldung per Karte am Sperrbildschirm) arbeitet die App für die Person, die gerade angemeldet ist. Der Weg zur Anlage führt über Rosenweg Gerät; die App selbst braucht dafür kein Netz und sieht kein Token.

| Thema | Verhalten |
|---|---|
| Wecker, Weltuhren, Timer | Beim Anmelden lädt die Uhr die Daten der Person und stellt ihre Wecker. Beim Abmelden und nach einem Neustart (er beendet jede Sitzung) entfernt sie alles wieder. Will ein Wecker klingeln, obwohl niemand angemeldet ist, klingelt er nicht, und die Daten werden entfernt. Hat die Person gewechselt, ohne dass die Uhr es mitbekam, räumt der nächste Abgleich zuerst auf. Fremde Wecker bleiben so nie liegen. Der Weg führt über Rosenweg Gerät (`portal_anfrage`) und die Telefonanlage; dort ist der Pfad freigegeben (`PORTAL_PFADE_STANDARD`). |
| Einstellungen pro Person | Nach dem Anmelden holt die App die Einstellungen der Person von der Telefonanlage; ist die Fassung dort neuer, gilt sie, sonst geht die hiesige hoch. Jede Änderung geht kurz danach hoch. Gerätebezogenes (Pfade, Zähler, Hinweise, PIN) bleibt auf dem Gerät. |
| Widgets | nach dem Übernehmen der Einstellungen von der Anlage werden die Widgets neu gezeichnet |
| Updates | Rosenweg Gerät installiert die App aus dem App-Katalog und hält sie aktuell. |
| Ohne Rosenweg Gerät | die Einstellungen bleiben auf dem Handy. |

Voraussetzung: die Rosenweg-Signatur. Rosenweg Gerät beantwortet Anfragen nur von Apps mit seiner Signatur (`ch.rosenweg4303.permission.ANLAGE`).

## Installation und Updates

| Wo | Wie |
|---|---|
| Geteilte Rosenweg-Telefone | Rosenweg Gerät installiert die App automatisch aus dem App-Katalog der Telefonanlage und hält sie aktuell. |
| Eigenes Handy | APK von [rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) herunterladen und installieren. Danach hält der Rosenweg Updater die Rosenweg-Apps aktuell. |

**Rosenweg Updater:** Auf Handys ohne Rosenweg Gerät prüft die App den App-Katalog der Telefonanlage und bietet Aktualisierungen für alle installierten Rosenweg-Apps an. Jede APK wird vorher geprüft (Prüfsumme, Paketname, Version, Rosenweg-Signatur); Android lässt jede Installation bestätigen.

Die App ist mit dem Rosenweg-Schlüssel signiert; Fassungen anderer Herkunft lassen sich nicht darüber installieren.

## Aufbau

Fossify-Code mit wenigen, markierten Rosenweg-Stellen (Kommentar „Rosenweg“). Der Kotlin-Namensraum bleibt `org.fossify.…`, nur die applicationId ist eigen: `ch.rosenweg4303.clock`. Kotlin-Dateien unten relativ zu `app/src/main/kotlin/org/fossify/clock/`.

| Teil | Inhalt |
|---|---|
| `helpers/AnlageEinstellungen.kt` | Einstellungen pro Person über Rosenweg Gerät (`content://ch.rosenweg4303.telefon.anlage`, Server `/api/telefon/einstellungen`) |
| `activities/MainActivity.kt` | Anmeldeschirm (eigenes Handy), ruft beim Start beide Abgleiche auf |
| `rosenweg/Abgleicher.kt` | Abgleich ohne Android: Änderungen erkennen, Server-Stand übernehmen, letzte Änderung gewinnt (Unit-Test `app/src/test/…/AbgleicherTest.kt`) |
| `rosenweg/UhrAblage.kt` | Wecker, Weltuhren und Timer in der Form des Backends; Ton auf diesem Gerät |
| `rosenweg/UhrAbgleich.kt` | Ablauf, WorkManager, An-/Abmelden am geteilten Telefon |
| `rosenweg/AbgleichWeg.kt` | Weg zur API: über Rosenweg Gerät oder direkt mit der Anmeldung der App |
| `rosenweg/RosenwegKontoActivity.kt` | Seite „Rosenweg-Konto und Abgleich“ in den Einstellungen |
| `helpers/DBHelper.kt`, `helpers/TimerHelper.kt`, `helpers/Config.kt` | melden Änderungen an den Abgleich |
| `app/src/main/AndroidManifest.xml` | Berechtigung `ch.rosenweg4303.permission.ANLAGE`, Empfänger für An-/Abmelden (nur Rosenweg-Signatur) |
| `ch.rosenweg4303:updater` | Rosenweg Updater (aus commons-rosenweg) |
| `ch.rosenweg4303:cloud` 0.13.0 | Anmeldeschirm (Karte, Rosenweg-Konto, QR), `RosenwegAnmeldung` (aus commons-rosenweg) |
| Commons `7.1.3-rosenweg.5` | Rosenweg-Fork der Fossify Commons, aus GitHub Packages (`gradle/libs.versions.toml`, `settings.gradle.kts`) |
| `assets/logo/` | Logo aus dem Baukasten von rosenweg-basis: Turm, Weg und Rose mit dem Zeichen der App; dazu monochromes Designsymbol (Android 13+). Neu erzeugen: `sh assets/logo/erzeugen.sh` |
| `app/src/main/res/`: `values/rosenweg_icon.xml`, `mipmap-anydpi-v26/ic_launcher.xml` | ein einziges Rosenweg-Symbol statt der wählbaren Fossify-Symbolfarben |
| `fastlane/` | Store-Texte und Bilder des Originals (nicht verwendet) |

## Bauen

Die App baut gegen den Rosenweg-Fork der Fossify Commons und die Rosenweg-Module `cloud`/`updater` (`ch.rosenweg4303:*`). Sie kommen aus GitHub Packages von commons-rosenweg. Dafür braucht es `gpr.user`/`gpr.key` in `~/.gradle/gradle.properties` oder `GITHUB_ACTOR`/`GPR_TOKEN` in der Umgebung. Ohne diese Angaben gilt das lokale Maven-Repository; dann zuerst [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg) veröffentlichen:

```bash
# in commons-rosenweg (Zweig main)
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal

# in diesem Repo (Zweig main)
./gradlew assembleFossRelease
```

Die APK liegt danach unter `app/build/outputs/apk/foss/release/`. Signiert wird mit `keystore.properties` (siehe [Konfiguration](#konfiguration)).

Logo neu erzeugen (rosenweg-basis als Nachbarordner): `sh assets/logo/erzeugen.sh`. Welches Zeichen die App trägt, steht in rosenweg-basis `werkzeug/logo/android-apps.txt`.

Mehr: [docs/entwickeln.md](docs/entwickeln.md).

## CI und Release

Gebaut und signiert wird lokal; die CI verteilt nur.

| Schritt | Was passiert |
|---|---|
| APK bauen und signieren | lokal, wie unter [Bauen](#bauen) |
| `gh release create v<versionName> <datei.apk> --notes "Was ist neu"` | Tag = versionName der APK, eine APK je Release; ohne `--target` landet der Tag auf `main` |
| Workflow `.github/workflows/apps.yml` | ruft `apps-release.yml` aus Rosenweg/Website auf und schickt die APK an **[rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps)** und in den App-Katalog der Telefonanlage – damit bekommen sie die geteilten Telefone und der Rosenweg Updater |
| Prüfung auf der Anlage | Anmeldung per GitHub-OIDC (kein gespeichertes Geheimnis); geprüft werden Repo, Tag, Prüfsumme, Rosenweg-Signatur und Paketname. Die App muss auf /smartphones schon angelegt sein. |
| Erneut senden | `gh workflow run apps.yml --ref <tag>` |

Die übrigen Workflows (`pr.yml`, `testing-build.yml`, `release.yml`, `update-commons.yml` …) stammen aus dem Original und rufen die Fossify-Vorlagen auf; der Play-Store-Release (`release.yml`) läuft nur bei einer Änderung an `.fossify/release-marker.txt`.

Weg auf dem Server: Rosenweg/Website, `pbx/README.md`, Abschnitt „Seite /apps und App-Releases“.

## Konfiguration

| Wo | Was (nur Namen) |
|---|---|
| `gradle.properties` | `APP_ID` (`ch.rosenweg4303.clock`), `VERSION_NAME`, `VERSION_CODE` |
| `keystore.properties` (nicht im Repo, Vorlage `keystore.properties_sample`) | Pfad und Zugang zum Rosenweg-Signierschlüssel |
| GitHub-Secrets | keine für den Weg nach /apps (OIDC) |
| Server (Rosenweg/Website, PBX-API) | Katalog-Eintrag `ch.rosenweg4303.clock` auf /smartphones (Gruppen, aktiv); Einstellungen je Person und Paket über `/api/telefon/einstellungen` |

## Zweige

`main` ist der Hauptzweig mit allen Rosenweg-Anpassungen; der frühere Arbeitszweig `rosenweg` ist darin gemergt. Neuerungen aus dem Original werden von [Fossify Clock](https://github.com/FossifyOrg/Clock) nach `main` übernommen.

## Herkunft und Lizenz

Basiert auf [Fossify Clock](https://github.com/FossifyOrg/Clock). Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0 (GPL-3.0), siehe [LICENSE](LICENSE).
