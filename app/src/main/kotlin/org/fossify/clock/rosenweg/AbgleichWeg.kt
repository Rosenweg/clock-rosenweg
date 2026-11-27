// detekt: Netz, Binder und Speicher scheitern auf viele Arten - der Abgleich faengt alles ab und
// versucht es spaeter wieder; Zahlen sind Masse/Zeiten der Oberflaeche und des Protokolls.
@file:Suppress(
    "TooGenericExceptionCaught", "SwallowedException", "MagicNumber", "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount",
)

package org.fossify.clock.rosenweg

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import ch.rosenweg4303.cloud.KartenSitzung
import ch.rosenweg4303.cloud.RosenwegAnmeldung
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Weg zur Haupt-API (rosenweg4303.ch), je nach Geraet:
 *
 *  - Geteiltes Telefon (Rosenweg Geraet installiert): ueber dessen Content-Provider
 *    (call "portal_anfrage"), im Namen der Person, die am Sperrbildschirm angemeldet ist. Die App
 *    sieht nie ein Token; die Anlage gibt die Anfrage nur fuer freigegebene Pfade weiter
 *    (pbx/api/app.py PORTAL_PFADE_STANDARD).
 *  - Eigenes Handy: direkt, mit der Anmeldung dieser App (commons-rosenweg cloud: Rosenweg-Konto,
 *    Karte oder QR-Code; RosenwegAnmeldung.zugangsToken deckt alle Wege ab).
 *
 * Blockiert (Netz): nicht im UI-Thread.
 */
object AbgleichWeg {
    private val TAG = UhrAbgleich.TAG
    private val ANLAGE = Uri.parse("content://ch.rosenweg4303.telefon.anlage")
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // ganze Anfrage inkl. DNS (connectTimeout deckt die Namensaufloesung nicht ab)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Anmeldung (commons cloud) mit Frist: deren HTTP hat kein Gesamt-Timeout. */
    private val frist = java.util.concurrent.Executors.newCachedThreadPool()
    private const val TOKEN_FRIST_S = 60L

    sealed class Antwort {
        /** [sitzung]: wechselt am geteilten Telefon mit jeder An-/Abmeldung (sonst null). */
        data class Ok(val status: Int, val json: String, val sitzung: String?) : Antwort()
        /** Niemand angemeldet (geteiltes Telefon) bzw. diese App nicht angemeldet (eigenes Handy). */
        object NichtAngemeldet : Antwort()
        data class Fehler(val text: String) : Antwort()
    }

    fun geteilt(c: Context) = RosenwegAnmeldung.geraetDa(c)

    /**
     * Geteiltes Telefon: ist jemand angemeldet? Ohne Netz (letzter Stand von Rosenweg Geraet; nach
     * einem Neustart sofort false). null = unbekannt (Geraet noch nicht eingerichtet, Fehler).
     */
    fun geteiltAngemeldet(c: Context): Boolean? = try {
        c.contentResolver.call(ANLAGE, "angemeldet", c.packageName, null)
            ?.takeIf { it.containsKey("angemeldet") }?.getBoolean("angemeldet")
    } catch (e: Exception) {
        Log.i(TAG, "angemeldet: ${e.javaClass.simpleName}")
        null
    }

    fun anfrage(c: Context, methode: String, pfad: String, koerper: String? = null): Antwort {
        val start = System.currentTimeMillis()
        val a = if (geteilt(c)) ueberGeraet(c, methode, pfad, koerper) else direkt(c, methode, pfad, koerper)
        val wie = when (a) {
            is Antwort.Ok -> "HTTP ${a.status}"
            is Antwort.NichtAngemeldet -> "nicht angemeldet"
            is Antwort.Fehler -> "Fehler: ${a.text}"
        }
        Log.i(TAG, "$methode ${pfad.substringBefore('?')} -> $wie (${System.currentTimeMillis() - start} ms)")
        return a
    }

    private fun token(c: Context): String? {
        val wie = if (RosenwegAnmeldung.mitKarte(c)) {
            "Karten-Sitzung (${KartenSitzung.weg(c) ?: "mit Karte"})"
        } else {
            "Rosenweg-Konto"
        }
        val start = System.currentTimeMillis()
        val fadenRef = java.util.concurrent.atomic.AtomicReference<Thread?>()
        val f = frist.submit(java.util.concurrent.Callable<String?> {
            fadenRef.set(Thread.currentThread())
            RosenwegAnmeldung.zugangsToken(c)
        })
        return try {
            f.get(TOKEN_FRIST_S, TimeUnit.SECONDS).also {
                val ms = System.currentTimeMillis() - start
                Log.i(TAG, "Token ($wie): ${if (it == null) "keins" else "da"} nach $ms ms")
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            val faden = fadenRef.get()
            // Wo steht der Faden? (Netz in KartenSitzung.erneuern, Sperre, Keystore ...)
            val stapel = faden?.stackTrace?.take(25)?.joinToString("\n  at ") ?: "?"
            Log.e(TAG, "Token ($wie) nach $TOKEN_FRIST_S s nicht da, Faden ${faden?.name} (${faden?.state}):" +
                "\n  at $stapel")
            f.cancel(true)
            throw java.io.IOException("Anmeldung antwortet nicht ($TOKEN_FRIST_S s)")
        } catch (e: java.util.concurrent.ExecutionException) {
            Log.w(TAG, "Token ($wie): ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
            throw e.cause ?: e
        }
    }

    private fun ueberGeraet(c: Context, methode: String, pfad: String, koerper: String?): Antwort {
        val b: Bundle = try {
            c.contentResolver.call(ANLAGE, "portal_anfrage", pfad, Bundle().apply {
                putString("methode", methode)
                if (koerper != null) putString("koerper", koerper)
            }) ?: return Antwort.Fehler("Rosenweg Gerät antwortet nicht")
        } catch (e: Exception) {
            // SecurityException: Rosenweg Geraet zu alt (kein portal_anfrage) oder andere Signatur
            Log.w(TAG, "portal_anfrage: ${e.javaClass.simpleName}")
            return Antwort.Fehler("Rosenweg Gerät erlaubt den Abgleich nicht")
        }
        if (b.getBoolean("nicht_angemeldet", false)) return Antwort.NichtAngemeldet
        b.getString("fehler")?.let { return Antwort.Fehler(it) }
        if (!b.containsKey("status")) return Antwort.Fehler("Rosenweg Gerät: unerwartete Antwort")
        return Antwort.Ok(b.getInt("status"), b.getString("json").orEmpty(), b.getString("sitzung"))
    }

    private fun direkt(c: Context, methode: String, pfad: String, koerper: String?): Antwort {
        repeat(2) { versuch ->
            val token = try {
                token(c)
            } catch (e: Exception) {
                Log.w(TAG, "Anmeldung: ${e.javaClass.simpleName}: ${e.message}")
                return Antwort.Fehler("Keine Verbindung zum Rosenweg")
            } ?: return Antwort.NichtAngemeldet
            val r = Request.Builder().url(KartenSitzung.API + pfad)
                .header("Authorization", "Bearer $token").header("Accept", "application/json")
                .method(methode, koerper?.toRequestBody(JSON))
                .build()
            try {
                http.newCall(r).execute().use { a ->
                    if (a.code == 401) Log.w(TAG, "$methode $pfad: 401 mit frischem Token (Versuch $versuch)")
                    if (a.code == 401 && versuch == 0) {
                        // Token im Speicher abgelaufen/widerrufen: einmal neu holen
                        RosenwegAnmeldung.zugangUngueltig()
                        return@repeat
                    }
                    return Antwort.Ok(a.code, a.body?.string().orEmpty(), null)
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "$methode $pfad: ${e.javaClass.simpleName}: ${e.message}")
                return Antwort.Fehler("Keine Verbindung zum Rosenweg")
            }
        }
        return Antwort.Fehler("Anmeldung abgelaufen – bitte neu anmelden")
    }
}
