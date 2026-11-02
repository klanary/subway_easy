package io.github.klanary.jigeumchulbal.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.klanary.jigeumchulbal.ui.common.Copy

data class PermissionRow(val title: String, val granted: Boolean)

/** 권한 상태 행. null은 그 기기에 해당 없음(알림은 Android 13 미만, 정확한 알람은 12 미만)이라 행을 뺀다. */
fun permissionRows(location: Boolean, notifications: Boolean?, exactAlarm: Boolean?): List<PermissionRow> = buildList {
    add(PermissionRow(Copy.PERMISSION_LOCATION, location))
    notifications?.let { add(PermissionRow(Copy.PERMISSION_NOTIFICATIONS, it)) }
    exactAlarm?.let { add(PermissionRow(Copy.PERMISSION_EXACT_ALARM, it)) }
}

/** 지금 기기의 권한 상태를 읽는다. */
fun currentPermissionRows(context: Context): List<PermissionRow> {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val location = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }
    val exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    } else {
        null
    }
    return permissionRows(location, notifications, exactAlarm)
}

/** [설정 열기]: 정확한 알람은 "알람 및 리마인더" 화면, 나머지는 앱 상세 설정. */
fun permissionSettingsIntent(context: Context, row: PermissionRow): Intent {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    return if (row.title == Copy.PERMISSION_EXACT_ALARM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }
}
