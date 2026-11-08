/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.model

/** A source selected in the Community Catalog, preserving the scope encoded by its Add link. */
data class SourceAddRequest(
    val url: String,
    val scope: SourceScope = SourceScope.Whole
)
