package org.fossify.clock.helpers

import android.content.Context
import org.fossify.clock.extensions.timerDb
import org.fossify.clock.models.Timer
import org.fossify.clock.models.TimerState
import org.fossify.clock.rosenweg.UhrAbgleich
import org.fossify.commons.helpers.ensureBackgroundThread

class TimerHelper(val context: Context) {
    private val timerDao = context.timerDb

    fun getTimers(callback: (timers: List<Timer>) -> Unit) {
        ensureBackgroundThread {
            callback.invoke(timerDao.getTimers())
        }
    }

    fun getTimer(timerId: Int, callback: (timer: Timer) -> Unit) {
        ensureBackgroundThread {
            callback.invoke(timerDao.getTimer(timerId)!!)
        }
    }

    fun tryGetTimer(timerId: Int, callback: (timer: Timer?) -> Unit) {
        ensureBackgroundThread {
            callback.invoke(timerDao.getTimer(timerId))
        }
    }

    fun findTimers(seconds: Int, label: String, callback: (timers: List<Timer>) -> Unit) {
        ensureBackgroundThread {
            callback.invoke(timerDao.findTimers(seconds, label))
        }
    }

    fun insertOrUpdateTimer(timer: Timer, callback: (id: Long) -> Unit = {}) {
        ensureBackgroundThread {
            val id = timerDao.insertOrUpdateTimer(timer)
            // Rosenweg: Timer-Vorlagen werden abgeglichen; das Ticken eines laufenden Timers nicht
            if (timer.state !is TimerState.Running) UhrAbgleich.lokalGeaendert(context)
            callback.invoke(id)
        }
    }

    fun deleteTimer(id: Int, callback: () -> Unit = {}) {
        ensureBackgroundThread {
            timerDao.deleteTimer(id)
            UhrAbgleich.lokalGeaendert(context)
            callback.invoke()
        }
    }

    fun deleteTimers(timers: List<Timer>, callback: () -> Unit = {}) {
        ensureBackgroundThread {
            timerDao.deleteTimers(timers)
            UhrAbgleich.lokalGeaendert(context)
            callback.invoke()
        }
    }
}
