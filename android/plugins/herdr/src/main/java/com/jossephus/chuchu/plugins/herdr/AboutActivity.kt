package com.jossephus.chuchu.plugins.herdr

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Shown only if something launches the discovery activity directly. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setText(R.string.about)
            setPadding(48, 48, 48, 48)
        })
    }
}
