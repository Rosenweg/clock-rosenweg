// detekt: Netz, Binder und Speicher scheitern auf viele Arten - der Abgleich faengt alles ab und
// versucht es spaeter wieder; Zahlen sind Masse/Zeiten der Oberflaeche und des Protokolls.
@file:Suppress(
    "TooGenericExceptionCaught", "SwallowedException", "MagicNumber", "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount",
)

package org.fossify.clock.rosenweg

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.timerDb
import org.fossify.clock.helpers.EDITED_TIME_ZONE_SEPARATOR
import org.fossify.clock.helpers.getAllTimeZones
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.Timer
import org.fossify.clock.models.TimerState
import org.fossify.commons.extensions.getDefaultAlarmSound
import org.fossify.commons.helpers.FRIDAY_BIT
import org.fossify.commons.helpers.MONDAY_BIT
import org.fossify.commons.helpers.SATURDAY_BIT
import org.fossify.commons.helpers.SILENT
import org.fossify.commons.helpers.SUNDAY_BIT
import org.fossify.commons.helpers.THURSDAY_BIT
import org.fossify.commons.helpers.TUESDAY_BIT
import org.fossify.commons.helpers.WEDNESDAY_BIT

/**
 * Die Daten der Uhr, die der Person gehoeren, in der Form des Backends (Eintraege je Objekt):
 *
 *   wecker   {zeit (Minuten ab Mitternacht), tage (Bits wie in der App), an, vibration,
 *             bezeichnung, einmalig, ton {titel, uri}}
 *   weltuhr  {zone (z.B. "Europe/Zurich"), titel (eigener Name oder null)}; Kennung "z<Id>" -
 *             dieselbe Stadt auf zwei Geraeten ist derselbe Eintrag
 *   timer    {sekunden, vibration, bezeichnung, ton {titel, uri}, erstellt}; ohne den Lauf (Start,
 *             Restzeit bleiben auf dem Geraet) und ohne Einmal-Timer (aus anderen Apps gestellt)
 *
 * Die Stoppuhr gehoert nicht dazu. Weckton: auf einem anderen Geraet gibt es dieselbe Datei
 * vielleicht nicht - dann der gleichnamige Ton dieses Geraets, sonst der Standard-Weckton.
 */
class UhrAblage(private val c: Context) : Ablage {
    companion object {
        const val WECKER = "wecker"
        const val WELTUHR = "weltuhr"
        const val TIMER = "timer"
        private const val TAG = "UhrAblage"
        private const val WERK_TIMER_SEKUNDEN = 300
        // Beispiel-Wecker ab Werk (DBHelper.insertInitialAlarms): 7:00 werktags, 9:00 am Wochenende
        private const val WERK_WECKER_WERKTAGS = 420
        private const val WERK_WECKER_WOCHENENDE = 540
        private const val MINUTEN_JE_TAG = 24 * 60
        private val WERKTAGE = MONDAY_BIT or TUESDAY_BIT or WEDNESDAY_BIT or THURSDAY_BIT or FRIDAY_BIT
        private val WOCHENENDE = SATURDAY_BIT or SUNDAY_BIT
    }

    override val arten = listOf(WECKER, WELTUHR, TIMER)

    // ─── lesen ──────────────────────────────────────────────────────────────

    override fun lokale(): List<Lokal> {
        val l = ArrayList<Lokal>()
        c.dbHelper.getAlarms().forEach { l.add(Lokal(WECKER, it.id.toString(), weckerDaten(it))) }
        val titel = eigeneTitel()
        val zonen = getAllTimeZones().associateBy { it.id }
        for (s in c.config.selectedTimeZones) {
            val id = s.toIntOrNull() ?: continue
            val z = zonen[id] ?: continue
            val d = buildJsonObject {
                put("zone", z.zoneName)
                put("titel", titel[id]?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            }
            l.add(Lokal(WELTUHR, id.toString(), d, festeId = "z$id"))
        }
        c.timerDb.getTimers().filter { !it.oneShot && it.id != null }.forEach {
            l.add(Lokal(TIMER, it.id.toString(), timerDaten(it)))
        }
        return l
    }

    private fun ton(titel: String, uri: String) = buildJsonObject {
        put("titel", titel)
        put("uri", uri)
    }

    private fun weckerDaten(a: Alarm) = buildJsonObject {
        put("zeit", a.timeInMinutes)
        put("tage", a.days)
        put("an", a.isEnabled)
        put("vibration", a.vibrate)
        put("bezeichnung", a.label)
        put("einmalig", a.oneShot)
        put("ton", ton(a.soundTitle, a.soundUri))
    }

    private fun timerDaten(t: Timer) = buildJsonObject {
        put("sekunden", t.seconds)
        put("vibration", t.vibrate)
        put("bezeichnung", t.label)
        put("ton", ton(t.soundTitle, t.soundUri))
        put("erstellt", t.createdAt)
    }

    private fun eigeneTitel(): Map<Int, String> = c.config.editedTimeZoneTitles.mapNotNull {
        val teile = it.split(EDITED_TIME_ZONE_SEPARATOR, limit = 2)
        teile.getOrNull(0)?.toIntOrNull()?.let { id -> id to (teile.getOrNull(1) ?: "") }
    }.toMap()

    override fun nurWerkseitig(art: String, lokale: List<Lokal>): Boolean = when (art) {
        WECKER -> {
            val alarme = c.dbHelper.getAlarms()
            alarme.size <= 2 && alarme.all {
                !it.isEnabled && !it.vibrate && it.label.isEmpty() &&
                    ((it.timeInMinutes == WERK_WECKER_WERKTAGS && it.days == WERKTAGE) ||
                        (it.timeInMinutes == WERK_WECKER_WOCHENENDE && it.days == WOCHENENDE))
            }
        }
        TIMER -> {
            val t = c.timerDb.getTimers().filter { !it.oneShot }
            t.size == 1 && t[0].label.isEmpty() && t[0].seconds == WERK_TIMER_SEKUNDEN && t[0].state is TimerState.Idle
        }
        else -> false   // Weltuhren gibt es ab Werk keine
    }

    // ─── schreiben ──────────────────────────────────────────────────────────

    private fun JsonObject.int(k: String, vorgabe: Int) = this[k]?.jsonPrimitive?.intOrNull ?: vorgabe
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** (Titel, Uri) eines Tons, wie es ihn auf diesem Geraet gibt. */
    private fun tonHier(d: JsonObject): Pair<String, String> {
        val t = d["ton"] as? JsonObject
        val titel = t?.text("titel").orEmpty()
        val uri = t?.text("uri").orEmpty()
        val standard = c.getDefaultAlarmSound(RingtoneManager.TYPE_ALARM)
        if (uri.isEmpty()) return standard.title to standard.uri
        if (uri == SILENT || tonDa(uri)) return titel to uri
        gleichnamigerTon(titel)?.let { return titel to it }
        return standard.title to standard.uri
    }

    private fun tonDa(uri: String): Boolean = try {
        val u = Uri.parse(uri)
        when (u.scheme) {
            "android.resource" -> true
            "content" -> c.contentResolver.query(u, null, null, null, null)?.use { it.moveToFirst() } ?: false
            "file" -> u.path?.let { java.io.File(it).canRead() } ?: false
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    private fun gleichnamigerTon(titel: String): String? {
        if (titel.isEmpty()) return null
        return try {
            val m = RingtoneManager(c).apply { setType(RingtoneManager.TYPE_ALARM) }
            val k = m.cursor
            var gefunden: String? = null
            while (gefunden == null && k.moveToNext()) {
                if (k.getString(RingtoneManager.TITLE_COLUMN_INDEX) == titel) {
                    gefunden = m.getRingtoneUri(k.position).toString()
                }
            }
            gefunden
        } catch (e: Exception) {
            Log.w(TAG, "Toene: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun alarmAus(id: Int, d: JsonObject): Alarm {
        val (titel, uri) = tonHier(d)
        return Alarm(
            id = id,
            timeInMinutes = d.int("zeit", WERK_WECKER_WERKTAGS).coerceIn(0, MINUTEN_JE_TAG - 1),
            days = d.int("tage", 0),
            isEnabled = d.bool("an"),
            vibrate = d.bool("vibration"),
            soundTitle = titel,
            soundUri = uri,
            label = d.text("bezeichnung").orEmpty(),
            oneShot = d.bool("einmalig"),
        )
    }

    /** Neu gestellt oder geaendert: im Wecker des Systems eintragen bzw. austragen. */
    private fun weckerPlanen(alt: Alarm?, neu: Alarm) {
        if (alt != null && alt.isEnabled) c.cancelAlarmClock(alt)
        if (neu.isEnabled) c.alarmController.scheduleNextOccurrence(neu, false)
    }

    override fun anlegen(art: String, id: String, daten: JsonObject): String? = when (art) {
        WECKER -> {
            val a = alarmAus(0, daten)
            val neu = c.dbHelper.insertAlarm(a)
            if (neu <= 0) null else neu.toString().also { weckerPlanen(null, a.copy(id = neu)) }
        }
        WELTUHR -> weltuhrSetzen(id, daten)
        TIMER -> {
            val (titel, uri) = tonHier(daten)
            val t = Timer(
                id = null,
                seconds = daten.int("sekunden", WERK_TIMER_SEKUNDEN).coerceAtLeast(1),
                state = TimerState.Idle,
                vibrate = daten.bool("vibration"),
                soundUri = uri,
                soundTitle = titel,
                label = daten.text("bezeichnung").orEmpty(),
                createdAt = daten["erstellt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                channelId = c.config.timerChannelId,
            )
            c.timerDb.insertOrUpdateTimer(t).takeIf { it > 0 }?.toString()
        }
        else -> null
    }

    override fun aendern(art: String, lokalId: String, daten: JsonObject): Boolean = when (art) {
        WECKER -> {
            val id = lokalId.toIntOrNull()
            val alt = id?.let { c.dbHelper.getAlarmWithId(it) }
            if (alt == null) {
                false
            } else {
                val neu = alarmAus(alt.id, daten)
                c.dbHelper.updateAlarm(neu).also { if (it) weckerPlanen(alt, neu) }
            }
        }
        WELTUHR -> weltuhrSetzen("z$lokalId", daten) != null
        TIMER -> {
            val alt = lokalId.toIntOrNull()?.let { c.timerDb.getTimer(it) }
            if (alt == null) {
                false
            } else {
                val (titel, uri) = tonHier(daten)
                // Ein laufender Timer laeuft weiter (Restzeit gehoert zum Geraet)
                c.timerDb.insertOrUpdateTimer(
                    alt.copy(
                        seconds = daten.int("sekunden", alt.seconds).coerceAtLeast(1),
                        vibrate = daten.bool("vibration"),
                        soundUri = uri,
                        soundTitle = titel,
                        label = daten.text("bezeichnung").orEmpty(),
                    )
                )
                true
            }
        }
        else -> false
    }

    override fun loeschen(art: String, lokalId: String) {
        when (art) {
            WECKER -> lokalId.toIntOrNull()?.let { id ->
                c.dbHelper.getAlarmWithId(id)?.let { c.dbHelper.deleteAlarms(arrayListOf(it)) }
            }
            WELTUHR -> lokalId.toIntOrNull()?.let { id ->
                c.config.selectedTimeZones = c.config.selectedTimeZones.filter { it != id.toString() }.toHashSet()
                titelSetzen(id, null)
            }
            TIMER -> lokalId.toIntOrNull()?.let { id ->
                val t = c.timerDb.getTimer(id) ?: return@let
                c.timerDb.deleteTimer(id)
                // laeuft er gerade: Countdown und Meldung beenden (App.kt)
                if (t.state !is TimerState.Idle) UhrAbgleich.timerBeenden(id)
            }
        }
    }

    /** Weltuhr "z<Id>" setzen (zone pruefen) -> lokale Id oder null (Zeitzone hier unbekannt). */
    private fun weltuhrSetzen(id: String, d: JsonObject): String? {
        val zonen = getAllTimeZones()
        val zone = d.text("zone")
        val nachId = id.removePrefix("z").toIntOrNull()?.let { n -> zonen.firstOrNull { it.id == n } }
        // Kennung passt nicht zur Zone (andere App-Fassung): nach dem Namen der Zone suchen
        val z = nachId?.takeIf { zone == null || it.zoneName == zone }
            ?: zonen.firstOrNull { it.zoneName == zone } ?: return null
        val gewaehlt = c.config.selectedTimeZones
        if (z.id.toString() !in gewaehlt) c.config.selectedTimeZones = (gewaehlt + z.id.toString()).toHashSet()
        titelSetzen(z.id, d.text("titel"))
        return z.id.toString()
    }

    private fun titelSetzen(id: Int, titel: String?) {
        val andere = c.config.editedTimeZoneTitles
            .filter { it.split(EDITED_TIME_ZONE_SEPARATOR, limit = 2)[0] != id.toString() }
        val neu = if (titel.isNullOrEmpty()) andere else andere + "$id$EDITED_TIME_ZONE_SEPARATOR$titel"
        if (neu.toSet() != c.config.editedTimeZoneTitles) c.config.editedTimeZoneTitles = neu.toHashSet()
    }

    /** Geteiltes Telefon, Abmelden: alles der Person weg (Wecker ausgetragen, Timer beendet). */
    fun allesLoeschen() {
        val alarme = c.dbHelper.getAlarms()
        if (alarme.isNotEmpty()) c.dbHelper.deleteAlarms(alarme)
        c.timerDb.getTimers().forEach { t ->
            t.id?.let { id ->
                c.timerDb.deleteTimer(id)
                if (t.state !is TimerState.Idle) UhrAbgleich.timerBeenden(id)
            }
        }
        c.config.selectedTimeZones = HashSet()
        c.config.editedTimeZoneTitles = HashSet()
    }
}
