package com.antoine.chatgptpower;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Stores the chosen launcher package in device-protected preferences. */
public final class LaunchTargetStore {
    private static final String PREFERENCES = "power_launcher_preferences";
    private static final String KEY_PACKAGE = "selected_package";
    private static final String KEY_LABEL = "selected_label";
    private static final String DEFAULT_PACKAGE = "com.openai.chatgpt";

    private LaunchTargetStore() {
    }

    public static void initializeDefaultTarget(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.contains(KEY_PACKAGE)) {
            return;
        }
        if (createLaunchIntentForPackage(context, DEFAULT_PACKAGE) != null) {
            setTarget(context, DEFAULT_PACKAGE, loadLabel(context, DEFAULT_PACKAGE));
        }
    }

    public static void setTarget(Context context, String packageName, String label) {
        preferences(context)
                .edit()
                .putString(KEY_PACKAGE, packageName)
                .putString(KEY_LABEL, label)
                .apply();
    }

    public static String getSelectedPackage(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.contains(KEY_PACKAGE)) {
            String packageName = preferences.getString(KEY_PACKAGE, null);
            return packageName == null || packageName.isEmpty() ? null : packageName;
        }
        return createLaunchIntentForPackage(context, DEFAULT_PACKAGE) == null
                ? null
                : DEFAULT_PACKAGE;
    }

    public static String getSelectedLabel(Context context) {
        String packageName = getSelectedPackage(context);
        if (packageName == null) {
            return null;
        }
        String installedLabel = loadLabel(context, packageName);
        if (installedLabel != null) {
            return installedLabel;
        }
        return preferences(context).getString(KEY_LABEL, packageName);
    }

    public static Intent createLaunchIntent(Context context) {
        String packageName = getSelectedPackage(context);
        return packageName == null ? null : createLaunchIntentForPackage(context, packageName);
    }

    public static boolean isSelectedTargetAvailable(Context context) {
        return createLaunchIntent(context) != null;
    }

    private static Intent createLaunchIntentForPackage(Context context, String packageName) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                return null;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return launchIntent;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String loadLabel(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    private static SharedPreferences preferences(Context context) {
        Context storageContext = context.getApplicationContext()
                .createDeviceProtectedStorageContext();
        return storageContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
