// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lucidreamer.AppContainer
import com.lucidreamer.LucidDreamerApp

@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as LucidDreamerApp).container

/**
 * Builds a ViewModel from the app's hand-rolled container.
 *
 * Replaces what a DI framework would generate, in about ten lines and with no
 * annotation processing. The `key` matters for screens that exist more than
 * once in the back stack, such as two different dream entries.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = appContainer()
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } },
    )
}
