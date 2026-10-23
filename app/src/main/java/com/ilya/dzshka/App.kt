package com.ilya.dzshka

import android.app.Application
import com.ilya.dzshka.data.Store
import com.ilya.dzshka.work.Alarms
import com.ilya.dzshka.work.Notif

class App : Application() {
    companion object {
        @Volatile var visible = false
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notif.ensureChannels(this)
        runCatching { Alarms.reschedule(this) }
        runCatching { com.ilya.dzshka.link.Link.ensureDeviceId() }
        runCatching { com.ilya.dzshka.work.LinkService.sync(this) }
        // забрать архив ДЗ с GitHub (и отправить своё, если есть токен)
        runCatching { if (!BuildConfig.HELPER && Store.d.gh.enabled) com.ilya.dzshka.net.GhSync.schedule(this, 3) }
    }
}
