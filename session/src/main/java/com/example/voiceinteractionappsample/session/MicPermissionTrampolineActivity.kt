package com.example.voiceinteractionappsample.session

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * [MicPermissionGate] のためだけの不可視トランポリン(issue #61)。
 * `VoiceInteractionSession` から実行時権限ダイアログを出すための唯一の手段 — OS の権限
 * ダイアログ自体は Activity のウィンドウ経由でしか表示できないため、可視コンテンツを
 * 持たない Activity(`Theme.Translucent.NoTitleBar`、AndroidManifest 側で指定)を一瞬だけ
 * 起動してすぐ畳む。
 */
class MicPermissionTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MicPermissionGate.isGranted(this)) {
            MicPermissionGate.deliverResult(true)
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        MicPermissionGate.deliverResult(granted)
        finish()
    }

    private companion object {
        const val REQUEST_CODE = 1
    }
}
