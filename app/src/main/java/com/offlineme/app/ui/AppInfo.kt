package com.offlineme.app.ui

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isBlocked: Boolean
)
