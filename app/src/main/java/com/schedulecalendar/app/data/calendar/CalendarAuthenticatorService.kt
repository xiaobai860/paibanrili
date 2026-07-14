package com.schedulecalendar.app.data.calendar

import android.accounts.Account
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 日历账户认证器服务
 * 向 Android 系统注册自定义账户类型（com.schedulecalendar.app），
 * 使应用创建的日历能在系统日历账户管理中被识别和显示。
 */
class CalendarAuthenticatorService : Service() {

    private lateinit var authenticator: CalendarAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = CalendarAuthenticator(this)
    }

    override fun onBind(intent: Intent): IBinder {
        return authenticator.iBinder
    }
}
