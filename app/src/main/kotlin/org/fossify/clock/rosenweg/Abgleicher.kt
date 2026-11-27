// detekt: Netz, Binder und Speicher scheitern auf viele Arten - der Abgleich faengt alles ab und
// versucht es spaeter wieder; Zahlen sind Masse/Zeiten der Oberflaeche und des Protokolls.
@file:Suppress(
    "TooGenericExceptionCaught", "SwallowedException", "MagicNumber", "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount",
)

package org.fossify.clock.rosenweg

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Abgleich persoenlicher App-Daten mit dem Rosenweg-Backend (Website api/routes/app-daten.js),
 * ohne Android - damit er als Unit-Test laeuft (AbgleicherTest). Die Uhr haengt nur ihre
 * [Ablage] an (Wecker, Weltuhren, Timer: UhrAblage).
 *
 * Modell des Servers: Eintraege (art, id, geaendert, geloescht, daten), je Eintrag gewinnt die
 * letzte Aenderung, Geloeschtes bleibt als Grabstein. Hier wird je Eintrag ein [Merker] gefuehrt:
 * welches lokale Objekt dazugehoert, wie es zuletzt aussah (Pruefsumme der daten), wann es zuletzt
 * geaendert wurde und ob die Aenderung noch hochgeladen werden muss.
 *
 * Aenderungen erkennt [erkennen] durch Vergleich mit dem Merker (keine Haken an jeder Stelle, die
 * einen Wecker aendert): neu, geaendert oder verschwunden (-> Grabstein). Der Zeitpunkt ist der des
 * Erkennens - die Uhr erkennt kurz nach jeder Aenderung (UhrAbgleich.lokalGeaendert), auch offline.
 */
@Serializable
data class Eintrag(
    val art: String,
    val id: String,
    val geaendert: Long,
    val geloescht: Boolean = false,
    val daten: JsonObject? = null,
    val version: Long = 0,
) {
    val schluessel get() = "$art/$id"
}

/** Ein lokales Objekt in der Form des Servers. [festeId]: Kennung ergibt sich aus dem Objekt (Weltuhr = Zeitzone). */
data class Lokal(val art: String, val lokalId: String, val daten: JsonObject, val festeId: String? = null)

@Serializable
data class Merker(
    val art: String,
    val id: String,
    /** lokale Kennung (Wecker-Id, Timer-Id, Zeitzonen-Id); null = geloescht */
    val lokal: String?,
    val pruefsumme: String,
    val geaendert: Long,
    val geloescht: Boolean = false,
    /** noch nicht hochgeladen */
    val offen: Boolean = false,
) {
    val schluessel get() = "$art/$id"
}

@Serializable
data class Stand(
    /** hoechste Version des Servers, die hier angekommen ist */
    val version: Long = 0,
    /** Person, deren Daten hier liegen (users.id laut Server) */
    val person: Long? = null,
    val merker: Map<String, Merker> = emptyMap(),
) {
    /** Noch nie mit dem Server abgeglichen (erster Abgleich: Werkseinstellungen beachten). */
    val neu get() = version == 0L && merker.isEmpty()
    fun offene() = merker.values.filter { it.offen }
}

/** Antwort des Servers (GET und PUT). */
@Serializable
data class Antwort(
    val version: Long = 0,
    val person: Person? = null,
    val eintraege: List<Eintrag> = emptyList(),
) {
    @Serializable
    data class Person(val id: Long = 0, val name: String? = null)
}

/** Was die App lokal tut. Alles blockierend, nicht im UI-Thread. */
interface Ablage {
    val arten: List<String>
    fun lokale(): List<Lokal>

    /** Neu anlegen -> lokale Kennung, oder null (nicht moeglich, z.B. unbekannte Zeitzone). */
    fun anlegen(art: String, id: String, daten: JsonObject): String?

    /** Bestehendes Objekt aendern; false = gibt es nicht (mehr). */
    fun aendern(art: String, lokalId: String, daten: JsonObject): Boolean
    fun loeschen(art: String, lokalId: String)

    /**
     * Lokale Kennungen der Eintraege ab Werk (z.B. die zwei Beispiel-Wecker), die noch unveraendert
     * sind. Sie stehen NICHT in [lokale] - sie gehen nie hoch.
     */
    fun werkseitige(art: String): List<String>
}

class Abgleicher(
    private val ablage: Ablage,
    private val jetzt: () -> Long = System::currentTimeMillis,
    private val neueId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    companion object {
        fun pruefsumme(daten: JsonObject): String =
            MessageDigest.getInstance("SHA-256").digest(daten.toString().toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
    }

    /** Lokale Aenderungen seit dem letzten Mal als offene Merker eintragen. */
    fun erkennen(stand: Stand, lokale: List<Lokal> = ablage.lokale()): Stand {
        val zeit = jetzt()
        val merker = stand.merker.toMutableMap()
        val vorhanden = HashSet<String>()
        // lokale Kennung -> Merker (je Art)
        val nachLokal = merker.values.filter { it.lokal != null }.associateBy { "${it.art}/${it.lokal}" }
        for (l in lokale) {
            val summe = pruefsumme(l.daten)
            val m = nachLokal["${l.art}/${l.lokalId}"] ?: l.festeId?.let { merker["${l.art}/$it"] }
            if (m == null) {
                val id = l.festeId ?: neueId()
                merker["${l.art}/$id"] = Merker(l.art, id, l.lokalId, summe, zeit, geloescht = false, offen = true)
                vorhanden.add("${l.art}/$id")
                continue
            }
            vorhanden.add(m.schluessel)
            if (m.geloescht || m.lokal != l.lokalId || m.pruefsumme != summe) {
                merker[m.schluessel] =
                    m.copy(lokal = l.lokalId, pruefsumme = summe, geaendert = zeit, geloescht = false, offen = true)
            }
        }
        for (m in stand.merker.values) {
            if (!m.geloescht && m.schluessel !in vorhanden) {
                merker[m.schluessel] =
                    m.copy(lokal = null, pruefsumme = "", geaendert = zeit, geloescht = true, offen = true)
            }
        }
        return stand.copy(merker = merker)
    }

    /** Offene Aenderungen fuer PUT (aktuelle daten der lokalen Objekte, Grabsteine ohne daten). */
    fun hochzuladen(stand: Stand, lokale: List<Lokal> = ablage.lokale()): List<Eintrag> {
        val nachLokal = lokale.associateBy { "${it.art}/${it.lokalId}" }
        return stand.offene().mapNotNull { m ->
            if (m.geloescht) {
                Eintrag(m.art, m.id, m.geaendert, geloescht = true)
            } else {
                // Objekt inzwischen weg: der naechste Abgleich erkennt das und schickt den Grabstein
                nachLokal["${m.art}/${m.lokal}"]?.let { Eintrag(m.art, m.id, m.geaendert, daten = it.daten) }
            }
        }
    }

    /**
     * Erster Abgleich: Eintraege ab Werk gehen nie hoch ([Ablage.werkseitige]). Hat das Konto von
     * dieser Art schon Daten, werden sie hier entfernt und die des Kontos uebernommen; ist es leer,
     * bleiben sie auf dem Geraet.
     */
    fun werkseitigeEntfernen(antwort: Antwort): Boolean {
        val oben = antwort.eintraege.filter { !it.geloescht }.map { it.art }.toSet()
        var entfernt = false
        for (art in oben) {
            for (id in ablage.werkseitige(art)) {
                ablage.loeschen(art, id)
                entfernt = true
            }
        }
        return entfernt
    }

    /**
     * Antwort des Servers uebernehmen. [gesendet]: Schluessel, die mit dieser Anfrage hochgingen
     * (sind danach nicht mehr offen, ausser das Objekt hat sich waehrenddessen wieder geaendert).
     * -> neuer Stand und ob lokal etwas geaendert wurde.
     */
    fun uebernehmen(stand: Stand, antwort: Antwort, gesendet: Map<String, Merker> = emptyMap()): Pair<Stand, Boolean> {
        val merker = stand.merker.toMutableMap()
        val lokale = ablage.lokale()
        val summeJetzt = lokale.associate { "${it.art}/${it.lokalId}" to pruefsumme(it.daten) }
        val festNachId = lokale.filter { it.festeId != null }.associateBy { "${it.art}/${it.festeId}" }
        // Hochgeladenes ist erledigt, wenn es seitdem nicht wieder geaendert wurde
        for ((k, g) in gesendet) {
            val m = merker[k] ?: continue
            if (m == g) merker[k] = m.copy(offen = false)
        }
        var geaendert = false
        for (e in antwort.eintraege) {
            if (e.art !in ablage.arten) continue
            val m = merker[e.schluessel]
            // Lokal waehrend der Anfrage geaendert oder neuer als der Server: nicht ueberschreiben,
            // der naechste Abgleich entscheidet (je Eintrag gewinnt die letzte Aenderung)
            if (m != null && m.offen && m.geaendert > e.geaendert) continue
            // Lokal geaendert oder geloescht, aber noch nicht erkannt: das ist die neueste Aenderung
            if (m?.lokal != null && summeJetzt["${m.art}/${m.lokal}"] != m.pruefsumme) continue
            if (e.geloescht) {
                if (m?.lokal != null && summeJetzt.containsKey("${m.art}/${m.lokal}")) {
                    ablage.loeschen(e.art, m.lokal)
                    geaendert = true
                }
                merker[e.schluessel] = Merker(e.art, e.id, null, "", e.geaendert, geloescht = true, offen = false)
                continue
            }
            val daten = e.daten ?: continue
            val summeOben = pruefsumme(daten)
            var lokalId = m?.lokal ?: festNachId[e.schluessel]?.lokalId
            val bekannt = lokalId != null && summeJetzt.containsKey("${e.art}/$lokalId")
            if (bekannt && summeJetzt["${e.art}/$lokalId"] == summeOben) {
                // schon so da (z.B. die eigene Aenderung kommt zurueck)
            } else if (bekannt && ablage.aendern(e.art, lokalId!!, daten)) {
                geaendert = true
            } else {
                lokalId = ablage.anlegen(e.art, e.id, daten)
                if (lokalId == null) {
                    // geht hier nicht (z.B. unbekannte Zeitzone): merken, nicht als Loeschen zurueckmelden
                    merker[e.schluessel] =
                        Merker(e.art, e.id, null, summeOben, e.geaendert, geloescht = true, offen = false)
                    continue
                }
                geaendert = true
            }
            merker[e.schluessel] =
                Merker(e.art, e.id, lokalId, summeOben, e.geaendert, geloescht = false, offen = false)
        }
        var neu = stand.copy(version = maxOf(stand.version, antwort.version), merker = merker)
        if (geaendert) {
            // Pruefsummen auf das setzen, was lokal wirklich steht (z.B. Weckton auf diesem Geraet
            // ersetzt): sonst sahe der naechste Abgleich eine Aenderung, die niemand gemacht hat
            neu = pruefsummenNachziehen(neu, antwort.eintraege.map { it.schluessel }.toSet())
        }
        return neu to geaendert
    }

    private fun pruefsummenNachziehen(stand: Stand, schluessel: Set<String>): Stand {
        val lokale = ablage.lokale().associateBy { "${it.art}/${it.lokalId}" }
        val merker = stand.merker.toMutableMap()
        for (k in schluessel) {
            val m = merker[k] ?: continue
            if (m.lokal == null || m.offen) continue
            val l = lokale["${m.art}/${m.lokal}"] ?: continue
            merker[k] = m.copy(pruefsumme = pruefsumme(l.daten))
        }
        return stand.copy(merker = merker)
    }
}
