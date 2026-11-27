// detekt: Netz, Binder und Speicher scheitern auf viele Arten - der Abgleich faengt alles ab und
// versucht es spaeter wieder; Zahlen sind Masse/Zeiten der Oberflaeche und des Protokolls.
@file:Suppress(
    "TooGenericExceptionCaught", "SwallowedException", "MagicNumber", "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount",
)

package org.fossify.clock.rosenweg

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ch.rosenweg4303.cloud.CloudStatus
import ch.rosenweg4303.cloud.RosenwegAnmeldeSchirm
import ch.rosenweg4303.cloud.RosenwegAnmeldung
import java.text.DateFormat
import java.util.Date

/**
 * "Rosenweg-Konto und Abgleich" in den Einstellungen: wer angemeldet ist und wie (Karte, QR-Code,
 * Rosenweg-Konto, geteiltes Telefon), Stand des Abgleichs, "Jetzt abgleichen", "Abmelden".
 *
 * Wie die Seite "Rosenweg-Nextcloud" der anderen Apps (commons-rosenweg AnmeldenActivity), aber
 * ohne Nextcloud - die Uhr braucht keine. Nicht angemeldet: gleich der gemeinsame Anmeldeschirm
 * (kein zweiter Anmeldeweg). Auf geteilten Telefonen wird am Sperrbildschirm an- und abgemeldet.
 */
class RosenwegKontoActivity : Activity() {
    companion object {
        /** Eine Zeile fuer den Eintrag in den Einstellungen. */
        fun kurz(c: Context): String {
            if (!AbgleichWeg.geteilt(c) && !RosenwegAnmeldung.angemeldet(c)) {
                return "Nicht angemeldet – tippen zum Anmelden"
            }
            return listOf(anmeldung(c), abgleich(c)).joinToString("\n")
        }

        fun anmeldung(c: Context): String {
            if (!AbgleichWeg.geteilt(c)) return CloudStatus.anmeldung(c)
            return when (AbgleichWeg.geteiltAngemeldet(c)) {
                true -> {
                    val wer = UhrAbgleich.name(c)?.takeIf { it.isNotBlank() }?.let { " als $it" } ?: ""
                    "Geteiltes Telefon: angemeldet$wer (über Rosenweg Gerät)"
                }
                false -> "Geteiltes Telefon: niemand angemeldet (Anmeldung am Sperrbildschirm)"
                null -> "Geteiltes Telefon: Rosenweg Gerät noch nicht eingerichtet"
            }
        }

        fun abgleich(c: Context): String {
            val ok = UhrAbgleich.zuletztOk(c)
            val fehler = UhrAbgleich.letzterFehlerText(c)
            val offen = UhrAbgleich.offen(c)
            val zeit = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            return buildString {
                append(if (ok == 0L) "Noch nicht abgeglichen" else "Zuletzt abgeglichen: ${zeit.format(Date(ok))}")
                if (fehler != null) append("\nLetzter Versuch fehlgeschlagen: $fehler")
                if (offen > 0) append("\n$offen Änderung(en) warten auf den Abgleich")
            }
        }
    }

    private lateinit var anmeldung: TextView
    private lateinit var stand: TextView
    private lateinit var jetzt: Button
    private lateinit var abmelden: Button
    @Volatile private var gleichtAb = false
    private var schirmGezeigt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rosenweg-Konto und Abgleich"
        val rand = dp(24)
        anmeldung = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        stand = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(12), 0, dp(16)) }
        jetzt = Button(this).apply { text = "Jetzt abgleichen"; setOnClickListener { jetztAbgleichen() } }
        abmelden = Button(this).apply { text = "Abmelden"; setOnClickListener { abmelden() } }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(rand, rand, rand, rand)
                addView(TextView(context).apply {
                    text = "Deine Wecker, Weltuhren und Timer gehören dir: Rosenweg Uhr gleicht sie mit dem " +
                        "Rosenweg ab, damit sie auf allen deinen Geräten gleich sind – auf dem eigenen Handy " +
                        "und an jedem geteilten Telefon, an dem du dich anmeldest. Ohne Netz geht alles " +
                        "weiter, abgeglichen wird später. Die Stoppuhr bleibt auf dem Gerät."
                    setPadding(0, 0, 0, dp(24))
                })
                addView(anmeldung)
                addView(stand)
                addView(jetzt)
                addView(abmelden)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        if (RosenwegAnmeldung.anmeldungNoetig(this)) {
            if (schirmGezeigt) {
                finish()   // vom Anmeldeschirm ohne Anmeldung zurueck
                return
            }
            schirmGezeigt = true
            startActivity(Intent(this, RosenwegAnmeldeSchirm::class.java)
                .putExtra(RosenwegAnmeldeSchirm.EXTRA_ZURUECK_SCHLIESST_APP, false))
            return
        }
        anzeigen()
    }

    private fun anzeigen() {
        val geteilt = AbgleichWeg.geteilt(this)
        Thread {
            // geteiltAngemeldet fragt Rosenweg Geraet (ohne Netz, aber ueber Binder): nicht im UI-Thread
            val a = anmeldung(applicationContext)
            val s = abgleich(applicationContext) +
                (if (geteilt) "\n\nAn- und abgemeldet wird am geteilten Telefon (Sperrbildschirm)." else "")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                anmeldung.text = a
                stand.text = if (gleichtAb) "Wird abgeglichen …" else s
                jetzt.isEnabled = !gleichtAb
                abmelden.visibility = if (geteilt) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun jetztAbgleichen() {
        if (gleichtAb) return
        gleichtAb = true
        anzeigen()
        val app = applicationContext
        Thread {
            val meldung = when (val e = UhrAbgleich.abgleichen(app)) {
                is UhrAbgleich.Ergebnis.Ok -> "Abgeglichen"
                is UhrAbgleich.Ergebnis.NichtAngemeldet -> "Nicht angemeldet"
                is UhrAbgleich.Ergebnis.Fehler -> "Abgleich fehlgeschlagen: ${e.text}"
            }
            runOnUiThread {
                gleichtAb = false
                if (!isFinishing) {
                    Toast.makeText(this, meldung, Toast.LENGTH_SHORT).show()
                    anzeigen()
                }
            }
        }.start()
    }

    private fun abmelden() {
        val offen = UhrAbgleich.offen(this)
        val text = "Aus Rosenweg Uhr abmelden? Deine Wecker bleiben auf diesem Handy, werden aber nicht " +
            "mehr abgeglichen." +
            (if (offen > 0) "\n\nAchtung: $offen Änderung(en) sind noch nicht abgeglichen." else "")
        AlertDialog.Builder(this)
            .setMessage(text)
            .setPositiveButton("Abmelden") { _, _ ->
                UhrAbgleich.vergessen(this)
                RosenwegAnmeldung.abmelden(this)
                Toast.makeText(this, "Abgemeldet", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
