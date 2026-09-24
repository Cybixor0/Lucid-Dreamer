// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db

import kotlinx.serialization.json.Json

/**
 * Shared JSON codec for config that is stored as a column and for the manual
 * backup file.
 *
 * - [Json.ignoreUnknownKeys] so a backup taken from a newer build still imports
 *   rather than failing outright.
 * - [Json.encodeDefaults] so exported files are explicit and readable instead of
 *   depending on whatever the defaults happened to be when they were written.
 * - [Json.prettyPrint] because the export is meant to be human-editable.
 */
val ConfigJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Compact variant for database columns, where readability is not worth the bytes. */
val ColumnJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
