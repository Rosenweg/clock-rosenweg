// detekt: Netz, Binder und Speicher scheitern auf viele Arten - der Abgleich faengt alles ab und
// versucht es spaeter wieder; Zahlen sind Masse/Zeiten der Oberflaeche und des Protokolls.
@file:Suppress(
    "TooGenericExceptionCaught", "SwallowedException", "MagicNumber", "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount",
)

package org.fossify.clock.rosenweg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ch.rosenweg4303.cloud.KartenSitzung
import ch.rosenweg4303.cloud.RosenwegAnmeldung
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fossify.clock.extensions.goAsync
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.models.AlarmEvent
import org.fossify.clock.models.TimerEvent
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit

/**
 * Rosenweg Uhr: Wecker, Weltuhren und Timer der Person auf allen ihren Geraeten gleich (Stefan
 * 27.11.2026: "damit es einen Mehrwert gibt: die Synchronisation mit dem Backend").
 * Backend: Website api/routes/app-daten.js (GET/PUT /api/me/app-daten/<paket>), Logik: [Abgleicher].
 *
 * Eigenes Handy: angemeldet wird mit dem gemeinsamen Anmeldeschirm (MainActivity). Abgleich beim
 * Oeffnen, kurz nach jeder Aenderung und stuendlich im Hintergrund (WorkManager, wartet auf Netz).
 * Offline geht alles; die Aenderungen gehen hoch, sobald wieder Netz da ist. Je Wecker gewinnt die
 * letzte Aenderung, Geloeschtes bleibt als Grabstein.
 *
 * Geteiltes Telefon (Rosenweg Geraet): ueber Rosenweg Geraet im Namen der angemeldeten Person.
 * Beim Anmelden (Broadcast ANGEMELDET) werden ihre Daten geladen und die Wecker gestellt; beim
 * Abmelden (ABGEMELDET, oder Rosenweg Geraet loescht die Daten der App) und nach einem Neustart
 * (beendet jede Sitzung) wird alles entfernt. Wechselt die Person, ohne dass die Uhr es mitbekam,
 * merkt es der naechste Abgleich (andere Person laut Server) und raeumt zuerst auf. Ein Wecker, der
 * klingeln will, obwohl niemand angemeldet ist, klingelt nicht (AlarmReceiver) - fremde Wecker
 * bleiben nie liegen.
 */
object UhrAbgleich {
    /** Ein Tag fuer alles am Abgleich (wie beim Kalender): adb logcat -s RosenwegUhr */
    const val TAG = "RosenwegUhr"
    /** Laenger darf niemand die Sperre halten, ohne dass es im Log steht (mit Stapel des Halters). */
    private const val SPERRE_WARTEN_S = 90L
    private const val PREFS = "rosenweg_uhr_abgleich"
    private const val ARBEIT_JETZT = "rosenweg-uhr-abgleich"
    private const val ARBEIT_STUENDLICH = "rosenweg-uhr-abgleich-stuendlich"
    private const val ERKENNEN_MS = 2000L
    const val ANGEMELDET = "ch.rosenweg4303.ANGEMELDET"
    const val ABGEMELDET = "ch.rosenweg4303.ABGEMELDET"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val sperre = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var halter: Thread? = null
    @Volatile private var halterSeit = 0L
    @Volatile private var halterWas = ""

    /**
     * Unter der Sperre ausfuehren; nach [SPERRE_WARTEN_S] aufgeben (null) und ins Log schreiben, wer
     * sie haelt und wo er steht - statt wie frueher mit synchronized endlos zu warten.
     */
    private fun <T> gesperrt(was: String, f: () -> T): T? {
        if (!sperre.tryLock(SPERRE_WARTEN_S, TimeUnit.SECONDS)) {
            val h = halter
            val stapel = h?.stackTrace?.take(12)?.joinToString("\n  at ") ?: "?"
            Log.e(TAG, "$was: Sperre seit ${(System.currentTimeMillis() - halterSeit) / 1000} s gehalten von " +
                "${h?.name} ($halterWas):\n  at $stapel")
            return null
        }
        halter = Thread.currentThread()
        halterSeit = System.currentTimeMillis()
        halterWas = was
        try {
            return f()
        } finally {
            halter = null
            sperre.unlock()
        }
    }
    private val hand = Handler(Looper.getMainLooper())

    /** Der Abgleich schreibt gerade selbst (Haken in DBHelper/TimerHelper/Config ignorieren). */
    @Volatile var uebernimmt = false
        private set

    @Serializable
    private data class Gespeichert(val stand: Stand = Stand(), val konto: String? = null, val name: String? = null)

    @Serializable
    private data class Hoch(val seit: Long, val geraet: String, val eintraege: List<Eintrag>)

    sealed class Ergebnis {
        object Ok : Ergebnis()
        object NichtAngemeldet : Ergebnis()
        data class Fehler(val text: String, val nochmal: Boolean) : Ergebnis()
    }

    private fun pfad(c: Context) = "/api/me/app-daten/${c.packageName}"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun laden(c: Context): Gespeichert = try {
        prefs(c).getString("stand", null)?.let { json.decodeFromString<Gespeichert>(it) } ?: Gespeichert()
    } catch (e: Exception) {
        Log.w(TAG, "Stand nicht lesbar, beginne neu: ${e.javaClass.simpleName}")
        Gespeichert()
    }

    private fun speichern(c: Context, g: Gespeichert) {
        prefs(c).edit().putString("stand", json.encodeToString(g)).commit()
    }

    // ─── Ausloeser ──────────────────────────────────────────────────────────

    private var erkennenLauf: Runnable? = null   // nur im Haupt-Thread

    /**
     * Haken: ein Wecker, Timer oder eine Weltuhr wurde hier geaendert. Kurz danach erkennen (auch
     * offline, damit der Zeitpunkt stimmt) und den Abgleich anstossen.
     */
    @JvmStatic
    fun lokalGeaendert(ctx: Context) {
        if (uebernimmt) return
        val c = ctx.applicationContext
        hand.post {
            erkennenLauf?.let { hand.removeCallbacks(it) }
            val r = Runnable {
                erkennenLauf = null
                Thread { erkennenJetzt(c); anstossen(c) }.start()
            }
            erkennenLauf = r
            hand.postDelayed(r, ERKENNEN_MS)
        }
    }

    /** Abgleich bald ausfuehren (wartet auf Netz) und den stuendlichen einrichten. */
    fun anstossen(c: Context) {
        if (!aktiv(c)) return
        val netz = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(c)
        wm.enqueueUniqueWork(
            // Nicht REPLACE: das brach einen laufenden Abgleich ab (WorkerStoppedException), dessen Faden
            // aber weiterlief und die Sperre hielt. Danach anhaengen genuegt: er erkennt dann neu.
            ARBEIT_JETZT, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<Arbeit>().setConstraints(netz).build()
        )
        wm.enqueueUniquePeriodicWork(
            ARBEIT_STUENDLICH, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Arbeit>(1, TimeUnit.HOURS).setConstraints(netz).build()
        )
    }

    /**
     * App geoeffnet: abgleichen. Geteiltes Telefon ohne angemeldete Person (sollte Rosenweg Geraet
     * gar nicht zulassen): zuerst aufraeumen.
     */
    fun beimOeffnen(ctx: Context) {
        val c = ctx.applicationContext
        Thread {
            if (AbgleichWeg.geteilt(c) && AbgleichWeg.geteiltAngemeldet(c) == false) personWeg(c) else anstossen(c)
        }.start()
    }

    /** Abgleich ueberhaupt moeglich (geteiltes Telefon oder diese App angemeldet)? */
    fun aktiv(c: Context) = AbgleichWeg.geteilt(c) || RosenwegAnmeldung.angemeldet(c)

    private fun erkennenJetzt(c: Context) {
        if (!aktiv(c)) return
        gesperrt("erkennen") {
            val g = laden(c)
            // Vor dem ersten Abgleich nichts erkennen: dort entscheidet sich, was ab Werk ist
            if (g.stand.neu) return@gesperrt
            val neu = Abgleicher(UhrAblage(c)).erkennen(g.stand)
            if (neu != g.stand) {
                speichern(c, g.copy(stand = neu))
                Log.i(TAG, "erkannt: ${neu.offene().size} Aenderung(en) offen")
            }
        }
    }

    // ─── Abgleich ───────────────────────────────────────────────────────────

    fun abgleichen(ctx: Context): Ergebnis {
        val c = ctx.applicationContext
        val geteilt = AbgleichWeg.geteilt(c)
        if (geteilt && AbgleichWeg.geteiltAngemeldet(c) == false) {
            personWeg(c)
            return Ergebnis.NichtAngemeldet
        }
        if (!geteilt && !RosenwegAnmeldung.angemeldet(c)) {
            Log.i(TAG, "Abgleich: nicht angemeldet")
            return Ergebnis.NichtAngemeldet
        }
        val start = System.currentTimeMillis()
        val weg = if (geteilt) "geteiltes Telefon, ueber Rosenweg Geraet" else "eigenes Handy, direkt"
        Log.i(TAG, "Abgleich Start ($weg)")
        val e = try {
            gesperrt("abgleichen") { durchgang(c, geteilt) }
                ?: Ergebnis.Fehler("Abgleich blockiert (siehe Log)", nochmal = true)
        } catch (e: Exception) {
            Log.e(TAG, "Abgleich: ${e.javaClass.simpleName}: ${e.message}", e)
            Ergebnis.Fehler("Unerwartete Antwort", nochmal = true)
        }
        val wie = when (e) {
            is Ergebnis.Ok -> "ok"
            is Ergebnis.NichtAngemeldet -> "nicht angemeldet"
            is Ergebnis.Fehler -> "Fehler: ${e.text}"
        }
        Log.i(TAG, "Abgleich Ende nach ${System.currentTimeMillis() - start} ms: $wie")
        status(c, e)
        return e
    }

    private fun durchgang(c: Context, geteilt: Boolean): Ergebnis {
        val ablage = UhrAblage(c)
        val ab = Abgleicher(ablage)
        var g = laden(c)
        var lokalGeaendert = false
        // Eigenes Handy, anderes Konto angemeldet: von vorn (die Wecker bleiben und kommen dazu)
        val konto = if (geteilt) null else RosenwegAnmeldung.email(c)
        if (!geteilt && g.konto != null && g.konto != konto) g = Gespeichert()
        g = g.copy(konto = konto)

        // Geteiltes Telefon und erster Abgleich: zuerst holen (wer ist angemeldet, was hat sie)
        val holenZuerst = geteilt || g.stand.neu
        if (holenZuerst) {
            var a = holen(c, if (g.stand.neu) 0 else g.stand.version) ?: return letzterFehler
            val person = a.person?.id
            if (g.stand.person != null && person != null && person != g.stand.person) {
                Log.i(TAG, "andere Person: von vorn")
                if (geteilt) {
                    mitUebernahme { ablage.allesLoeschen() }
                    lokalGeaendert = true
                }
                g = Gespeichert(konto = konto)
                a = holen(c, 0) ?: return letzterFehler
            }
            if (g.stand.neu && geteilt) {
                lokalGeaendert = mitUebernahme { ablage.herrenloseEntfernen() } || lokalGeaendert
            }
            Log.i(TAG, "geholt: Version ${a.version}, ${a.eintraege.size} Eintrag/Eintraege" +
                " (${a.eintraege.count { it.geloescht }} Grabsteine)")
            if (g.stand.neu) lokalGeaendert = mitUebernahme { ab.werkseitigeEntfernen(a) } || lokalGeaendert
            val (s, geaendert) = mitUebernahme { ab.uebernehmen(g.stand.copy(person = person ?: g.stand.person), a) }
            g = g.copy(stand = s, name = a.person?.name ?: g.name)
            lokalGeaendert = lokalGeaendert || geaendert
            speichern(c, g)
        }

        // Lokale Aenderungen erkennen und hochladen; ohne vorheriges Holen auch ohne Aenderungen
        // (PUT liefert alles seit der letzten Version mit)
        var stand = ab.erkennen(g.stand)
        val hoch = ab.hochzuladen(stand)
        if (hoch.isNotEmpty() || !holenZuerst) {
            val gesendet = stand.offene().associateBy { it.schluessel }
            val koerper = json.encodeToString(Hoch(stand.version, KartenSitzung.geraetName(), hoch))
            Log.i(TAG, "hoch: ${hoch.size} Eintrag/Eintraege (${hoch.count { it.geloescht }} Grabsteine)," +
                " seit ${stand.version}")
            val a = senden(c, koerper) ?: run {
                speichern(c, g.copy(stand = stand))   // Erkanntes behalten, spaeter nochmals
                return letzterFehler
            }
            Log.i(TAG, "Antwort: Version ${a.version}, ${a.eintraege.size} Eintrag/Eintraege herunter" +
                " (${a.eintraege.count { it.geloescht }} Grabsteine)")
            val vorher = stand.copy(person = a.person?.id ?: stand.person)
            val (s, geaendert) = mitUebernahme { ab.uebernehmen(vorher, a, gesendet) }
            stand = s
            g = g.copy(name = a.person?.name ?: g.name)
            lokalGeaendert = lokalGeaendert || geaendert
        }
        speichern(c, g.copy(stand = stand))
        if (lokalGeaendert) anzeigeAuffrischen(c)
        Log.i(TAG, "Stand: Version ${stand.version}, ${stand.merker.values.count { !it.geloescht }} Eintraege, " +
            "${stand.offene().size} offen, lokal geaendert: $lokalGeaendert")
        return Ergebnis.Ok
    }

    @Volatile private var letzterFehler: Ergebnis = Ergebnis.Fehler("", nochmal = true)

    private fun holen(c: Context, seit: Long): Antwort? =
        auswerten(AbgleichWeg.anfrage(c, "GET", "${pfad(c)}?seit=$seit"))

    private fun senden(c: Context, koerper: String): Antwort? =
        auswerten(AbgleichWeg.anfrage(c, "PUT", pfad(c), koerper))

    private fun auswerten(r: AbgleichWeg.Antwort): Antwort? {
        letzterFehler = when (r) {
            is AbgleichWeg.Antwort.NichtAngemeldet -> Ergebnis.NichtAngemeldet
            is AbgleichWeg.Antwort.Fehler -> Ergebnis.Fehler(r.text, nochmal = true)
            is AbgleichWeg.Antwort.Ok -> if (r.status == 200) {
                try {
                    return json.decodeFromString<Antwort>(r.json)
                } catch (e: Exception) {
                    Log.w(TAG, "Antwort: ${e.javaClass.simpleName}")
                    Ergebnis.Fehler("Unerwartete Antwort des Rosenweg", nochmal = true)
                }
            } else {
                // 4xx ausser 401/429: nochmal hilft nicht (z.B. 403 Pfad auf der Anlage nicht freigegeben)
                Ergebnis.Fehler(fehlerText(r), nochmal = r.status >= 500 || r.status == 429 || r.status == 401)
            }
        }
        return null
    }

    private fun fehlerText(r: AbgleichWeg.Antwort.Ok): String = try {
        val j = Json.parseToJsonElement(r.json) as? kotlinx.serialization.json.JsonObject
        (j?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
    } catch (e: Exception) {
        null
    } ?: "Fehler ${r.status}"

    private inline fun <T> mitUebernahme(f: () -> T): T {
        uebernimmt = true
        try {
            return f()
        } finally {
            uebernimmt = false
        }
    }

    /** Geteiltes Telefon, niemand (mehr) angemeldet: alles der Person entfernen. */
    fun personWeg(ctx: Context) {
        val c = ctx.applicationContext
        val entfernt = gesperrt("abmelden") {
            val g = laden(c)
            val ablage = UhrAblage(c)
            if (g.stand.merker.isEmpty() && g.stand.person == null && ablage.lokale().isEmpty()) return@gesperrt false
            mitUebernahme { ablage.allesLoeschen() }
            prefs(c).edit().clear().commit()
            Log.i(TAG, "abgemeldet: Wecker, Weltuhren und Timer der Person entfernt")
            true
        }
        if (entfernt != true) return
        WorkManager.getInstance(c).cancelUniqueWork(ARBEIT_JETZT)
        anzeigeAuffrischen(c)
    }

    /** Eigenes Handy, abgemeldet: nicht mehr abgleichen (die Daten bleiben auf dem Handy). */
    fun vergessen(ctx: Context) {
        val c = ctx.applicationContext
        gesperrt("vergessen") { prefs(c).edit().clear().commit() }
        WorkManager.getInstance(c).cancelUniqueWork(ARBEIT_JETZT)
        WorkManager.getInstance(c).cancelUniqueWork(ARBEIT_STUENDLICH)
    }

    /** Laufenden Timer beenden (Countdown in App.kt, Meldung), nachdem er geloescht wurde. */
    fun timerBeenden(id: Int) {
        hand.post { EventBus.getDefault().post(TimerEvent.Delete(id)) }
    }

    private fun anzeigeAuffrischen(c: Context) {
        hand.post {
            c.updateWidgets()
            EventBus.getDefault().post(AlarmEvent.Refresh)
            EventBus.getDefault().post(TimerEvent.Refresh)
        }
    }

    // ─── "Nur auf diesem Geraet" ────────────────────────────────────────────

    /*
     * Stefan 27.11.2026: je Wecker die Option "Nur auf diesem Geraet" (Standard aus). Solche Wecker
     * bleiben lokal und gehen nicht hoch; war einer schon beim Server, wird er dort beim Umschalten
     * als Grabstein entfernt (UhrAblage.lokale laesst ihn aus). Eigene Datei: bleibt beim Abmelden.
     * Auf geteilten Telefonen gibt es die Option nicht (dort ist alles an die Person gebunden).
     */
    private const val NUR_HIER = "rosenweg_uhr_nur_hier"

    fun nurHierMoeglich(c: Context) = !AbgleichWeg.geteilt(c)

    fun nurHier(c: Context): Set<Int> =
        c.applicationContext.getSharedPreferences(NUR_HIER, Context.MODE_PRIVATE)
            .getStringSet("wecker", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    /**
     * Neuer Wecker (DBHelper.insertAlarm). Ohne Anmeldung gestellt - eigenes Handy vor der Anmeldung,
     * geteiltes Telefon ohne angemeldete Person, z.B. ueber SET_ALARM einer anderen App: er gehoert
     * niemandem und geht nicht ins Konto der Person, die sich danach anmeldet. Eigenes Handy: er
     * bleibt als "Nur auf diesem Geraet" (im Wecker umschaltbar). Geteiltes Telefon: er wird bei der
     * naechsten Anmeldung entfernt (UhrAblage.herrenloseEntfernen), klingelt vorher nicht (AlarmReceiver).
     */
    fun neuerWecker(c: Context, weckerId: Int) {
        if (uebernimmt || weckerId <= 0) return
        val niemand = if (AbgleichWeg.geteilt(c)) {
            AbgleichWeg.geteiltAngemeldet(c) != true
        } else {
            !RosenwegAnmeldung.angemeldet(c)
        }
        if (niemand) nurHierSetzen(c, weckerId, true)
    }

    fun nurHierSetzen(c: Context, weckerId: Int, nurHier: Boolean) {
        if (weckerId <= 0) return
        val p = c.applicationContext.getSharedPreferences(NUR_HIER, Context.MODE_PRIVATE)
        val alt = p.getStringSet("wecker", emptySet()).orEmpty()
        val neu = if (nurHier) alt + weckerId.toString() else alt - weckerId.toString()
        if (neu == alt) return
        p.edit().putStringSet("wecker", neu.toHashSet()).commit()
        lokalGeaendert(c)
    }

    // ─── Stand fuer die Einstellungen ───────────────────────────────────────

    private fun status(c: Context, e: Ergebnis) {
        val p = prefs(c).edit()
        when (e) {
            is Ergebnis.Ok -> p.putLong("ok_um", System.currentTimeMillis()).remove("fehler")
            is Ergebnis.Fehler -> p.putString("fehler", e.text.ifEmpty { "Abgleich fehlgeschlagen" })
                .putLong("fehler_um", System.currentTimeMillis())
            is Ergebnis.NichtAngemeldet -> p.remove("fehler")
        }
        p.apply()
    }

    /** Zeitpunkt des letzten gelungenen Abgleichs (0 = noch keiner). */
    fun zuletztOk(c: Context) = prefs(c).getLong("ok_um", 0)
    fun letzterFehlerText(c: Context): String? = prefs(c).getString("fehler", null)
    fun offen(c: Context) = laden(c).stand.offene().size
    /** Name der Person laut Server (geteiltes Telefon: wer am Sperrbildschirm angemeldet ist). */
    fun name(c: Context): String? = laden(c).name
    fun anzahl(c: Context): Int = laden(c).stand.merker.values.count { !it.geloescht }

    // ─── WorkManager und Broadcasts ─────────────────────────────────────────

    class Arbeit(c: Context, p: WorkerParameters) : Worker(c, p) {
        override fun doWork(): Result {
            Log.i(TAG, "Arbeit beginnt (Versuch $runAttemptCount)")
            return ergebnis(abgleichen(applicationContext))
        }

        override fun onStopped() {
            Log.i(TAG, "Arbeit von WorkManager gestoppt (laeuft weiter bis zum Ende des Durchgangs)")
        }

        private fun ergebnis(e: Ergebnis): Result = when (e) {
            is Ergebnis.Ok, is Ergebnis.NichtAngemeldet -> Result.success()
            is Ergebnis.Fehler -> if (e.nochmal && runAttemptCount < 5) Result.retry() else Result.success()
        }
    }

    /** Rosenweg Geraet: Person am geteilten Telefon an- bzw. abgemeldet (nur Rosenweg-Signatur). */
    class Empfaenger : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ANGEMELDET -> anstossen(c)
                ABGEMELDET -> goAsync { personWeg(c) }
            }
        }
    }
}
