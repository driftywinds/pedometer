/*
    Privacy Friendly Pedometer is licensed under the GPLv3.
    Copyright (C) 2017  Tobias Neidig

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <http://www.gnu.org/licenses/>.
*/
package org.secuso.privacyfriendlyactivitytracker.utils;

import android.content.Context;
import android.content.res.TypedArray;

/**
 * Small helper to resolve Material 3 theme colors for views that draw themselves or that are
 * not themed automatically (for example the chart library).
 */
public final class ThemeUtils {

    private ThemeUtils() {
    }

    /**
     * Resolves a color attribute of the current theme.
     *
     * @param context The context which provides the theme
     * @param attr    A theme attribute such as {@code com.google.android.material.R.attr.colorPrimary}
     * @return The resolved color, or 0 if the attribute is not defined
     */
    public static int getThemeColor(Context context, int attr) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, 0);
        } finally {
            a.recycle();
        }
    }
}
