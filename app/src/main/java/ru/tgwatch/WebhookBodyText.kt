package ru.tgwatch

import android.content.Context

/** Translate once at transport admission using the application's current resources. */
internal fun WebhookBody.resolved(ctx: Context): WebhookBody = textValue?.let {
    WebhookBody(contentType, AndroidText.resolve(ctx, it).toByteArray(Charsets.UTF_8))
} ?: this
