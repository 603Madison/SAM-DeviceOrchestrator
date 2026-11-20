package com.orchestrator.app

import android.app.Application

class OrchestratorApp : Application() {
    /** Built when first needed: by the first screen, service or call from another app. */
    val container: Container by lazy { Container(this) }

    override fun onCreate() {
        super.onCreate()
        // Starts the line, when someone is signed in.
        container
    }
}

val android.content.Context.container: Container get() = (applicationContext as OrchestratorApp).container
