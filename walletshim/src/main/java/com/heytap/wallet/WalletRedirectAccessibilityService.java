package com.heytap.wallet;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Redirects ColorOS's hard-coded Chinese Wallet quick-launch activity to this shim.
 *
 * <p>The service subscribes only to window-state events from {@code com.heytap.tas}; its XML
 * configuration explicitly prevents it from retrieving any window content.
 */
public final class WalletRedirectAccessibilityService extends AccessibilityService {
    private static final String TAG = "WalletRedirect";
    private static final String OPPO_WALLET_PACKAGE = "com.heytap.tas";
    private static final String OPPO_QUICK_LAUNCH_ACTIVITY =
            "com.nearme.wallet.nfc.ui.NfcConsumeActivity";
    private static final long EVENT_DEBOUNCE_MS = 500L;
    private static final long POST_LAUNCH_GUARD_MS = 1_500L;
    private static final long REDIRECT_DELAY_MS = 75L;
    private static final long UNLOCK_POLL_INTERVAL_MS = 50L;
    private static final long UNLOCK_TIMEOUT_MS = 60_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable unlockPoll = this::pollForUnlock;
    private final Runnable completeRedirect = this::completeRedirect;
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOffObserved = true;
                return;
            }
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                screenOffObserved = false;
                if (pendingUnlockRedirectAt != 0L) {
                    mainHandler.removeCallbacks(unlockPoll);
                    mainHandler.post(unlockPoll);
                }
            }
        }
    };

    private KeyguardManager keyguardManager;
    private PowerManager powerManager;
    private boolean receiverRegistered;
    private boolean screenOffObserved;
    private boolean sawKeyguardLocked;
    private long lastOemEventAt;
    private long lastCompletedRedirectAt;
    private long pendingUnlockRedirectAt;

    @Override
    public void onCreate() {
        super.onCreate();
        keyguardManager = getSystemService(KeyguardManager.class);
        powerManager = getSystemService(PowerManager.class);
        screenOffObserved = powerManager != null && !powerManager.isInteractive();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Persistent OPPO Wallet redirect enabled");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        if (!TextUtils.equals(OPPO_WALLET_PACKAGE, packageName)
                || !TextUtils.equals(OPPO_QUICK_LAUNCH_ACTIVITY, className)) {
            return;
        }

        // Remove the OEM activity from the foreground first. Otherwise it can resume when the
        // biometric prompt closes and generate another accessibility event, causing a loop.
        boolean dismissed = performGlobalAction(GLOBAL_ACTION_BACK);
        long now = SystemClock.elapsedRealtime();

        if (pendingUnlockRedirectAt != 0L) {
            Log.i(TAG, "Suppressed duplicate OPPO Wallet launch while redirect is pending");
            return;
        }
        if (lastOemEventAt != 0L && now - lastOemEventAt < EVENT_DEBOUNCE_MS) {
            Log.i(TAG, "Suppressed duplicate OPPO Wallet window event");
            return;
        }
        if (lastCompletedRedirectAt != 0L
                && now - lastCompletedRedirectAt < POST_LAUNCH_GUARD_MS) {
            Log.i(TAG, "Suppressed late OPPO Wallet event after completed redirect");
            return;
        }

        lastOemEventAt = now;
        pendingUnlockRedirectAt = now;
        sawKeyguardLocked = false;
        Log.i(TAG, "OPPO Wallet quick launch detected; dismissed=" + dismissed);

        if (isKeyguardLocked() || screenOffObserved) {
            mainHandler.removeCallbacks(unlockPoll);
            mainHandler.post(unlockPoll);
            Log.i(TAG, "Waiting for normal keyguard authentication");
        } else {
            mainHandler.removeCallbacks(completeRedirect);
            mainHandler.postDelayed(completeRedirect, REDIRECT_DELAY_MS);
            Log.i(TAG, "Device already unlocked; scheduling immediate redirect");
        }
    }

    private void pollForUnlock() {
        if (pendingUnlockRedirectAt == 0L) {
            return;
        }

        long elapsed = SystemClock.elapsedRealtime() - pendingUnlockRedirectAt;
        if (elapsed >= UNLOCK_TIMEOUT_MS) {
            Log.i(TAG, "Discarded locked Wallet request after unlock timeout");
            clearPendingUnlockRedirect();
            return;
        }

        if (isKeyguardLocked()) {
            sawKeyguardLocked = true;
            mainHandler.postDelayed(unlockPoll, UNLOCK_POLL_INTERVAL_MS);
            return;
        }
        if (screenOffObserved && !sawKeyguardLocked) {
            // The screen-on broadcast precedes ColorOS publishing its keyguard state.
            mainHandler.postDelayed(unlockPoll, UNLOCK_POLL_INTERVAL_MS);
            return;
        }

        screenOffObserved = false;
        Log.i(TAG, "Keyguard authentication completed; scheduling Google Wallet");
        mainHandler.removeCallbacks(completeRedirect);
        mainHandler.postDelayed(completeRedirect, REDIRECT_DELAY_MS);
    }

    private boolean isKeyguardLocked() {
        return keyguardManager == null
                || keyguardManager.isKeyguardLocked()
                || keyguardManager.isDeviceLocked();
    }

    private void completeRedirect() {
        if (pendingUnlockRedirectAt == 0L) {
            return;
        }
        clearPendingUnlockRedirect();
        lastCompletedRedirectAt = SystemClock.elapsedRealtime();
        openWalletShim();
    }

    private void clearPendingUnlockRedirect() {
        pendingUnlockRedirectAt = 0L;
        sawKeyguardLocked = false;
        mainHandler.removeCallbacks(unlockPoll);
        mainHandler.removeCallbacks(completeRedirect);
    }

    private void openWalletShim() {
        Intent intent = new Intent(this, MainActivity.class)
                .setAction("heytap.wallet.intent.action.OPEN")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(intent);
            Log.i(TAG, "Google Wallet shim activity started");
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to start Google Wallet shim", exception);
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "Persistent OPPO Wallet redirect interrupted");
    }

    @Override
    public void onDestroy() {
        pendingUnlockRedirectAt = 0L;
        mainHandler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(screenStateReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
