package com.heytap.wallet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Reinstates the redirect after ColorOS clears third-party accessibility services at boot. */
public final class AccessibilityRestoreReceiver extends BroadcastReceiver {
    private static final String TAG = "WalletRestore";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.w(TAG, "Ignored unexpected restore broadcast: " + action);
            return;
        }
        AccessibilityStateRepair.restoreIfAuthorized(context, action);
    }
}
