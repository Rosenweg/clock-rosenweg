package org.fossify.clock.rosenweg

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Abgleich ohne Android: lokale Ablage als Map, Server als Liste von Eintraegen. */
class AbgleicherTest {
    /** Wecker (lokale Id -> daten) und Weltuhren (Zonen-Id -> daten) wie in UhrAblage. */
    private class Speicher : Ablage {
        val wecker = linkedMapOf<String, JsonObject>()
        val welt = linkedMapOf<String, JsonObject>()
        var naechste = 1
        /** Eintraege ab Werk (lokale Wecker-Ids) wie in UhrAblage: nie in lokale() */
        val werk = mutableSetOf<String>()
        /** "Nur auf diesem Geraet" wie in UhrAblage: solche Wecker sieht der Abgleich nicht */
        val nurHier = mutableSetOf<String>()
        override val arten = listOf("wecker", "weltuhr")
        override fun lokale() = wecker.filterKeys { it !in nurHier && it !in werk }
            .map { (k, v) -> Lokal("wecker", k, v) } +
            welt.map { (k, v) -> Lokal("weltuhr", k, v, festeId = "z$k") }

        override fun anlegen(art: String, id: String, daten: JsonObject): String? = when (art) {
            "wecker" -> (naechste++).toString().also { wecker[it] = daten }
            else -> id.removePrefix("z").also { welt[it] = daten }
        }

        override fun aendern(art: String, lokalId: String, daten: JsonObject): Boolean {
            val m = if (art == "wecker") wecker else welt
            if (lokalId !in m) return false
            m[lokalId] = daten
            return true
        }

        override fun loeschen(art: String, lokalId: String) {
            (if (art == "wecker") wecker else welt).remove(lokalId)
        }

        override fun werkseitige(art: String) = if (art == "wecker") werk.filter { it in wecker } else emptyList()
    }

    private fun an(version: Long, vararg e: Eintrag) = Antwort(version, eintraege = e.toList())
    private fun w(zeit: Int, an: Boolean = true) = buildJsonObject { put("zeit", zeit); put("an", an) }
    private var uhr = 1000L
    private var ids = 0
    private fun abgleicher(s: Speicher) = Abgleicher(s, { uhr }, { "id${++ids}" })

    @Test
    fun neuGeaendertGeloeschtWerdenErkannt() {
        val s = Speicher()
        val a = abgleicher(s)
        s.wecker["1"] = w(420)
        var st = a.erkennen(Stand())
        assertEquals(1, st.offene().size)
        val m = st.offene()[0]
        assertEquals("id1", m.id)
        assertEquals(1000L, m.geaendert)
        // Hochgeladen (Server nimmt an): nicht mehr offen
        st = a.uebernehmen(st, an(1, Eintrag("wecker", "id1", 1000, daten = w(420), version = 1)),
            st.merker).first
        assertTrue(st.offene().isEmpty())
        assertEquals(1L, st.version)
        // Nichts geaendert: nichts offen
        uhr = 2000
        assertTrue(a.erkennen(st).offene().isEmpty())
        // Geaendert
        s.wecker["1"] = w(480)
        val g = a.erkennen(st)
        assertEquals(2000L, g.offene().single().geaendert)
        assertEquals(480, a.hochzuladen(g).single().daten!!["zeit"]!!.jsonPrimitive.int)
        // Geloescht -> Grabstein
        s.wecker.clear()
        uhr = 3000
        val t = a.erkennen(st)
        val e = a.hochzuladen(t).single()
        assertTrue(e.geloescht)
        assertNull(e.daten)
        assertEquals(3000L, e.geaendert)
    }

    @Test
    fun serverAenderungWirdUebernommenLokalNeueresBleibt() {
        val s = Speicher()
        val a = abgleicher(s)
        // Vom Server: neuer Wecker, neue Weltuhr
        var (st, geaendert) = a.uebernehmen(Stand(), Antwort(3, eintraege = listOf(
            Eintrag("wecker", "x", 500, daten = w(360), version = 2),
            Eintrag("weltuhr", "z12", 500, daten = buildJsonObject { put("zone", "Europe/Zurich") }, version = 3),
        )))
        assertTrue(geaendert)
        assertEquals(360, s.wecker["1"]!!["zeit"]!!.jsonPrimitive.int)
        assertTrue("12" in s.welt)
        assertTrue(a.erkennen(st).offene().isEmpty())

        // Lokal spaeter geaendert, Server schickt aelteren Stand: lokal bleibt, bleibt offen
        uhr = 900
        s.wecker["1"] = w(390)
        st = a.erkennen(st)
        st = a.uebernehmen(st, an(4, Eintrag("wecker", "x", 800, daten = w(300), version = 4))).first
        assertEquals(390, s.wecker["1"]!!["zeit"]!!.jsonPrimitive.int)
        assertEquals(1, st.offene().size)

        // Server neuer (anderes Geraet hat spaeter geaendert): Server gewinnt
        st = a.uebernehmen(st, an(5, Eintrag("wecker", "x", 950, daten = w(330), version = 5))).first
        assertEquals(330, s.wecker["1"]!!["zeit"]!!.jsonPrimitive.int)
        assertTrue(st.offene().isEmpty())

        // Grabstein vom Server: lokal weg
        st = a.uebernehmen(st, an(6, Eintrag("wecker", "x", 990, geloescht = true, version = 6))).first
        assertTrue(s.wecker.isEmpty())
        assertTrue(st.merker["wecker/x"]!!.geloescht)
        assertTrue(a.erkennen(st).offene().isEmpty())
    }

    @Test
    fun nochNichtErkannteLokaleAenderungWirdNichtUeberschrieben() {
        val s = Speicher()
        val a = abgleicher(s)
        var st = a.uebernehmen(Stand(), an(1, Eintrag("wecker", "x", 500, daten = w(360), version = 1))).first
        // Person aendert, Haken noch nicht gelaufen; Server bringt etwas Aelteres derselben Sorte
        s.wecker["1"] = w(400)
        st = a.uebernehmen(st, an(2, Eintrag("wecker", "x", 600, daten = w(361), version = 2))).first
        assertEquals(400, s.wecker["1"]!!["zeit"]!!.jsonPrimitive.int)
        uhr = 2000
        assertEquals(400, a.hochzuladen(a.erkennen(st)).single().daten!!["zeit"]!!.jsonPrimitive.int)
    }

    @Test
    fun weltuhrMitFesterKennungKeineDoppelten() {
        val s = Speicher()
        val a = abgleicher(s)
        s.welt["12"] = buildJsonObject { put("zone", "Europe/Zurich"); put("titel", JsonPrimitive("Zuhause")) }
        // Dieselbe Stadt kommt vom Server: kein zweiter Eintrag, gleiche Kennung
        val (st, _) = a.uebernehmen(Stand(), Antwort(1, eintraege = listOf(
            Eintrag(
                "weltuhr", "z12", 500, version = 1,
                daten = buildJsonObject { put("zone", "Europe/Zurich"); put("titel", JsonPrimitive("Bern")) },
            ),
        )))
        assertEquals(1, s.welt.size)
        assertEquals("Bern", s.welt["12"]!!["titel"]!!.jsonPrimitive.content)
        val erkannt = a.erkennen(st)
        assertTrue(erkannt.offene().isEmpty())
        // Lokal entfernt und wieder gewaehlt: derselbe Eintrag lebt wieder auf
        s.welt.clear()
        val weg = a.erkennen(erkannt)
        assertEquals("z12", weg.offene().single().id)
        s.welt["12"] = buildJsonObject { put("zone", "Europe/Zurich") }
        val wieder = a.erkennen(weg)
        assertFalse(wieder.merker["weltuhr/z12"]!!.geloescht)
        assertEquals(1, wieder.merker.size)
    }

    @Test
    fun werkseitigeGehenNieHochUndWeichenDenDatenDesKontos() {
        val s = Speicher()
        val a = abgleicher(s)
        // Zwei Beispiel-Wecker ab Werk und ein eigener
        s.wecker["1"] = w(420, an = false)
        s.wecker["2"] = w(540, an = false)
        s.werk.addAll(listOf("1", "2"))
        s.wecker["3"] = w(600)
        s.naechste = 4
        // Konto leer: Werks-Wecker bleiben lokal, gehen aber nicht hoch; der eigene schon
        assertFalse(a.werkseitigeEntfernen(Antwort(0)))
        assertEquals(listOf(600), a.hochzuladen(a.erkennen(Stand())).map { it.daten!!["zeit"]!!.jsonPrimitive.int })
        assertEquals(3, s.wecker.size)
        // Konto hat Wecker: Werks-Wecker weg, die des Kontos kommen, der eigene bleibt
        val oben = an(1, Eintrag("wecker", "x", 500, daten = w(300), version = 1))
        assertTrue(a.werkseitigeEntfernen(oben))
        a.uebernehmen(Stand(), oben)
        assertEquals(listOf(600, 300), s.wecker.values.map { it["zeit"]!!.jsonPrimitive.int })
    }

    @Test
    fun hochgeladeneWerkseintraegeWerdenZuGrabsteinen() {
        // Vor 1.7.2 gingen die Beispiel-Wecker hoch; jetzt als ab Werk erkannt -> Grabstein, lokal bleibt er
        val s = Speicher()
        val a = abgleicher(s)
        s.wecker["1"] = w(420, an = false)
        var st = a.erkennen(Stand())
        val e = Eintrag("wecker", "id1", 1000, daten = w(420, an = false), version = 1)
        st = a.uebernehmen(st, an(1, e), st.merker).first
        uhr = 2000
        s.werk.add("1")
        val hoch = a.hochzuladen(a.erkennen(st)).single()
        assertTrue(hoch.geloescht)
        assertEquals("id1", hoch.id)
        assertTrue("1" in s.wecker)
    }

    @Test
    fun abgelehnteAenderungBringtServerStand() {
        val s = Speicher()
        val a = abgleicher(s)
        var st = a.uebernehmen(Stand(), an(1, Eintrag("wecker", "x", 500, daten = w(360), version = 1))).first
        uhr = 600
        s.wecker["1"] = w(370)
        st = a.erkennen(st)
        val gesendet = st.offene().associateBy { it.schluessel }
        // Server: ein anderes Geraet hat um 700 geloescht -> unsere Aenderung abgelehnt, Grabstein kommt
        st = a.uebernehmen(st, an(2, Eintrag("wecker", "x", 700, geloescht = true, version = 2)), gesendet).first
        assertTrue(s.wecker.isEmpty())
        assertTrue(st.offene().isEmpty())
    }

    @Test
    fun nurAufDiesemGeraetGehtNichtHochUndEntferntDieServerKopie() {
        val s = Speicher()
        val a = abgleicher(s)
        // Neuer Wecker, gleich "nur hier": nie hochladen
        s.wecker["1"] = w(300)
        s.nurHier.add("1")
        assertTrue(a.hochzuladen(a.erkennen(Stand())).isEmpty())

        // Abgeglichener Wecker wird umgeschaltet: Grabstein fuer den Server, lokal bleibt er
        s.wecker["2"] = w(420)
        var st = a.erkennen(Stand())
        st = a.uebernehmen(st, an(1, Eintrag("wecker", "id1", 1000, daten = w(420), version = 1)), st.merker).first
        uhr = 2000
        s.nurHier.add("2")
        st = a.erkennen(st)
        val hoch = a.hochzuladen(st).single()
        assertTrue(hoch.geloescht)
        assertEquals("id1", hoch.id)
        // Der Grabstein kommt zurueck: der lokale Wecker bleibt
        st = a.uebernehmen(st, an(2, hoch.copy(version = 2)), st.offene().associateBy { it.schluessel }).first
        assertEquals(420, s.wecker["2"]!!["zeit"]!!.jsonPrimitive.int)
        assertTrue(st.offene().isEmpty())

        // Wieder abgleichen: geht als neuer Eintrag hoch
        uhr = 3000
        s.nurHier.remove("2")
        val wieder = a.hochzuladen(a.erkennen(st)).single()
        assertFalse(wieder.geloescht)
        assertEquals(420, wieder.daten!!["zeit"]!!.jsonPrimitive.int)
    }
}
