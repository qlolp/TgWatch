package ru.tgwatch;

import android.content.res.Resources;

/** Pass prepared arguments to Android's vararg API without another Kotlin spread copy. */
final class ResourceTextFormat {
    private ResourceTextFormat() {}

    static String string(Resources resources, int id, Object[] arguments) {
        return arguments.length == 0 ? resources.getString(id) : resources.getString(id, arguments);
    }

    static String quantity(Resources resources, int id, int count, Object[] arguments) {
        return resources.getQuantityString(id, count, arguments);
    }
}
