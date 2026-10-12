package com.pokelocation.app.util;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

public final class Perms {
    private Perms() {}

    public static boolean hasFine(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean gpsEnabled(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canDrawOverlays(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** true abaixo do API 33. */
    public static boolean notificationsGranted(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean devOptionsEnabled(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
    }

    /** AppOps "android:mock_location" == MODE_ALLOWED. */
    @SuppressWarnings("deprecation")
    public static boolean isMockApp(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow("android:mock_location", Process.myUid(), c.getPackageName())
                    : ops.checkOpNoThrow("android:mock_location", Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** API 33+ e instalador ≠ Play Store: "Restricted settings" provavelmente bloqueia o overlay. */
    public static boolean restrictedSettingsLikely(Context c) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            String installer = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
            return !"com.android.vending".equals(installer);
        } catch (Exception e) {
            return true;
        }
    }

    public static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null));
    }

    public static Intent overlaySettings(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", c.getPackageName(), null));
    }

    public static Intent devSettings() {
        return new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
    }

    public static Intent locationSourceSettings() {
        return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    public static Intent notificationSettings(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        }
        return appDetails(c);
    }
}
