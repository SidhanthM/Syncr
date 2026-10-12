package dev.syncr.prototype

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

// Step 1: minimal launchable activity. Ink comes in step 2.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TextView(this).apply {
            text = "Syncr step 1: build + install works"
            gravity = Gravity.CENTER
        }.also { setContentView(it) }
    }
}
