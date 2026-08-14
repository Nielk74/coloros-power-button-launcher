package com.antoine.chatgptpower;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Authenticates when needed, then forwards the shortcut to the chosen app. */
public final class PowerShortcutActivity extends Activity {
    private static final String TAG = "PowerLauncher";
    private static final long KEYGUARD_POLL_MS = 100L;
    private static final long KEYGUARD_SETTLE_MS = 1800L;
    private static final int MAX_KEYGUARD_POLLS = 50;
    private boolean dismissRequested;
    private boolean targetLaunched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureLockedWindow();
        startMonitor();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dismissRequested = false;
        targetLaunched = false;
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        openTargetOrRequestUnlock();
    }

    private void configureLockedWindow() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(PowerButtonMonitorService.LAUNCH_NOTIFICATION_ID);
        }

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.BLACK);
        setContentView(content);
    }

    private void startMonitor() {
        Intent monitor = new Intent(this, PowerButtonMonitorService.class)
                .setAction(PowerButtonMonitorService.ACTION_REFRESH);
        try {
            startForegroundService(monitor);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not start the power-button monitor", exception);
        }
    }

    private void openTargetOrRequestUnlock() {
        if (targetLaunched || dismissRequested || isFinishing()) {
            return;
        }

        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null
                && keyguardManager.isKeyguardLocked()) {
            dismissRequested = true;
            Log.i(TAG, "Phone is locked; requesting authentication before opening target");
            keyguardManager.requestDismissKeyguard(
                    this,
                    new KeyguardManager.KeyguardDismissCallback() {
                        @Override
                        public void onDismissError() {
                            dismissRequested = false;
                            Log.w(TAG, "Keyguard dismissal could not be started");
                            getWindow().getDecorView().postDelayed(
                                    PowerShortcutActivity.this::openTargetOrRequestUnlock,
                                    250L);
                        }

                        @Override
                        public void onDismissCancelled() {
                            Log.i(TAG, "Unlock cancelled; selected-app launch cancelled");
                            finish();
                        }

                        @Override
                        public void onDismissSucceeded() {
                            Log.i(TAG, "Phone authenticated; waiting for keyguard transition");
                            waitForKeyguardThenLaunch(0);
                        }
                    });
            return;
        }

        launchTarget();
    }

    private void waitForKeyguardThenLaunch(final int pollCount) {
        if (targetLaunched || isFinishing()) {
            return;
        }
        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null
                && keyguardManager.isKeyguardLocked()
                && pollCount < MAX_KEYGUARD_POLLS) {
            getWindow().getDecorView().postDelayed(
                    () -> waitForKeyguardThenLaunch(pollCount + 1),
                    KEYGUARD_POLL_MS);
            return;
        }

        // ColorOS briefly restores its pre-lock task while completing the
        // keyguard animation. The service's USER_PRESENT path handles the
        // fast launch; this remains the conservative fallback.
        getWindow().getDecorView().postDelayed(() -> {
            Log.i(TAG, "Keyguard transition settled; opening selected app");
            launchTarget();
        }, KEYGUARD_SETTLE_MS);
    }

    private void launchTarget() {
        if (targetLaunched || isFinishing()) {
            return;
        }
        targetLaunched = true;
        Intent target = LaunchTargetStore.createLaunchIntent(this);
        if (target == null) {
            openChooser();
            return;
        }
        try {
            startActivity(target);
            overridePendingTransition(0, 0);
            finish();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not open the selected app", exception);
            openChooser();
        }
    }

    private void openChooser() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_TARGET_MISSING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not open the app picker", exception);
        }
        finish();
    }
}
