package com.schweik.nmdl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.schweik.nmdl.ui.MainScreen
import com.schweik.nmdl.ui.NmdlTheme

/**
 * 唯一的界面入口。
 *
 * 存储权限分两条路：Android 11+ 要跳系统设置里开「所有文件访问权限」，
 * 更早的版本走普通的读写外部存储运行时权限。
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private var hasPermission by mutableStateOf(false)

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = checkPermission() }

    // 从系统设置页回来后重新查一次，用户可能刚授权
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasPermission = checkPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasPermission = checkPermission()
        setContent {
            NmdlTheme {
                MainScreen(
                    vm = vm,
                    hasStoragePermission = hasPermission,
                    onRequestPermission = ::requestPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermission = checkPermission()
    }

    private fun checkPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 先试带包名的直达页；某些 ROM 不认，再退回总列表
            val direct = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            settingsLauncher.launch(
                if (direct.resolveActivity(packageManager) != null) direct else fallback
            )
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }
}
