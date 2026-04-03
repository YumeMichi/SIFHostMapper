package com.yumemichi.sifhostmapper

import android.app.Application
import com.google.android.material.color.DynamicColors

class SifHostMapperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
