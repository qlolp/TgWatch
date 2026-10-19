package ru.tgwatch

import android.content.Context

/** Resolve semantic values before handing Android a resource's numbered format arguments. */
internal fun Context.renderString(resource: Int, vararg values: Any?): String =
    ResourceTextFormat.string(resources, resource, values.map {
        if (it is TextValue) AndroidText.resolve(this, it) else it
    }.toTypedArray())
