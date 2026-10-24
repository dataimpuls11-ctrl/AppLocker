package com.example.applocker

import android.app.admin.DeviceAdminReceiver

// While this is an active device admin, Android refuses to uninstall AppLocker itself.
class AdminReceiver : DeviceAdminReceiver()
