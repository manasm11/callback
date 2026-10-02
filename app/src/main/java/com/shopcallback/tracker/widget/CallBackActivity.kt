package com.shopcallback.tracker.widget

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Invisible hop for a widget row tap: decides call vs dial when the tap happens, not when the
 * widget was drawn, so a CALL_PHONE grant revoked since the last render can't crash the tap.
 */
class CallBackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        if (!number.isNullOrBlank()) {
            val canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            try {
                startActivity(callBackIntent(number, canCall))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no app to handle the call-back", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "call-back refused", e)
            }
        }
        // Theme.NoDisplay requires finishing before onResume completes.
        finish()
    }

    companion object {
        private const val TAG = "CallBackActivity"
        const val EXTRA_PHONE_NUMBER = "com.shopcallback.tracker.widget.EXTRA_PHONE_NUMBER"
    }
}
