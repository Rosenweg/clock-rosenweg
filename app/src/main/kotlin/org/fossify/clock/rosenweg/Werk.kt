@file:Suppress("MagicNumber")

package org.fossify.clock.rosenweg

import android.content.Context

/**
 * Eintraege ab Werk (Stefan 27.11.2026): die zwei Fossify-Beispielwecker (7:00 werktags, 9:00 am
 * Wochenende, aus) und der Standard-Timer (5 Min). Jede Installation legt sie neu an - gingen sie
 * hoch, haette das Konto nach jedem neuen Geraet zwei Wecker und einen Timer mehr.
 *
 * Darum: beim Anlegen ab Werk gemerkt (DBHelper.insertInitialAlarms, AppDatabase.insertDefaultTimer),
 * nicht an der Uhrzeit erkannt. Solche Eintraege gehen nie hoch (UhrAblage.lokale laesst sie aus).
 * Hat das Konto beim ersten Abgleich schon Daten dieser Art, werden sie lokal entfernt; sonst
 * bleiben sie auf dem Geraet. Aendert die Person einen (z.B. Wecker eingeschaltet), gehoert er ihr
 * und wird ab dann abgeglichen. Pruefsumme des Werkzustands: beim ersten Ansehen gemerkt.
 *
 * Fassungen vor 1.7.2 haben nicht gemerkt ([uebernehmenAlteFassung]): einmalig gelten die ersten
 * Eintraege der Datenbank (Wecker-Id 1/2, Timer-Id 1), wenn sie noch genau ab Werk aussehen. Waren sie
 * schon hochgeladen, schickt der Abgleich dafuer Grabsteine (sie fehlen jetzt in lokale()).
 */
object Werk {
    private const val PREFS = "rosenweg_uhr_werk"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun merken(c: Context, art: String, id: Int) {
        if (id <= 0) return
        val alt = p(c).getStringSet(art, emptySet()).orEmpty()
        p(c).edit().putStringSet(art, (alt + id.toString()).toHashSet()).commit()
    }

    fun ids(c: Context, art: String): Set<Int> =
        p(c).getStringSet(art, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    /** Pruefsumme, wie der Eintrag ab Werk aussah (null = noch nicht gemerkt). */
    fun summe(c: Context, art: String, id: Int): String? = p(c).getString("summe_${art}_$id", null)

    fun summeMerken(c: Context, art: String, id: Int, summe: String) {
        p(c).edit().putString("summe_${art}_$id", summe).apply()
    }

    /** Die Person hat ihn geaendert: ab jetzt ihr Eintrag. */
    fun vergessen(c: Context, art: String, id: Int) {
        val alt = p(c).getStringSet(art, emptySet()).orEmpty()
        p(c).edit().putStringSet(art, (alt - id.toString()).toHashSet()).remove("summe_${art}_$id").commit()
    }

    fun alteFassungErledigt(c: Context) = p(c).getBoolean("alte_fassung", false)
    fun alteFassungErledigtSetzen(c: Context) {
        p(c).edit().putBoolean("alte_fassung", true).commit()
    }
}
