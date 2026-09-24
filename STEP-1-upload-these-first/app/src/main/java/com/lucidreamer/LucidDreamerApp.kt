// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer

import android.app.Application

/**
 * Application entry point.
 *
 * Dependencies are wired by hand through [AppContainer] rather than by a DI
 * framework. For a project this size a service locator is less machinery, no
 * code generation, and one less thing that can break a build.
 */
class LucidDreamerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
