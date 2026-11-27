package org.fossify.clock.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.goAsync
import org.fossify.clock.rosenweg.AbgleichWeg
import org.fossify.clock.rosenweg.UhrAbgleich

/**
 * Receiver responsible for rescheduling alarms in background.
 */
class RescheduleAlarmsReceiver : BroadcastReceiver() {

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        goAsync {
            // Rosenweg: nach einem Neustart ist am geteilten Telefon niemand mehr angemeldet (Rosenweg
            // Geraet beendet jede Sitzung) - die Wecker der letzten Person nicht neu stellen, sondern entfernen
            if (AbgleichWeg.geteilt(context) && AbgleichWeg.geteiltAngemeldet(context) == false) {
                UhrAbgleich.personWeg(context)
            } else {
                context.alarmController.rescheduleEnabledAlarms()
                UhrAbgleich.anstossen(context)
            }
        }
    }
}
