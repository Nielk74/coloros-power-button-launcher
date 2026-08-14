package com.antoine.chatgptpower;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts the physical power-button monitor after a reboot or app update. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerLauncher";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Intent service = new Intent(context, PowerButtonMonitorService.class)
                .setAction(PowerButtonMonitorService.ACTION_REFRESH);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not restart the power-button monitor", exception);
        }
    }
}
