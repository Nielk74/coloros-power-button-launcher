package com.antoine.chatgptpower;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Detects real ColorOS power-key downs from the narrowly filtered OEM policy log. */
public final class PowerButtonMonitorService extends Service {
    private static final String TAG = "PowerLauncher";
    public static final String ACTION_REAUTHORIZE_LOGS =
            "com.antoine.chatgptpower.action.REAUTHORIZE_LOGS";
    public static final String ACTION_TARGET_CHANGED =
            "com.antoine.chatgptpower.action.TARGET_CHANGED";
    public static final String ACTION_REFRESH =
            "com.antoine.chatgptpower.action.REFRESH";
    private static final String CHANNEL_ID = "power_button_shortcut";
    private static final String LAUNCH_CHANNEL_ID = "power_button_lock_launch";
    private static final int NOTIFICATION_ID = 2602;
    public static final int LAUNCH_NOTIFICATION_ID = 2603;
    private static final long MIN_DOUBLE_PRESS_MS = 60L;
    private static final long MAX_DOUBLE_PRESS_MS = 400L;
    private static final long LAUNCH_DELAY_MS = 420L;
    private static final long READER_WARM_UP_MS = 800L;
    private static final long LAUNCH_COOLDOWN_MS = 1000L;
    private static final long UNLOCK_POLL_MS = 200L;
    private static final long UNLOCK_SETTLE_MS = 1800L;
    private static final long USER_PRESENT_LAUNCH_DELAY_MS = 150L;
    private static final long LOCKED_TAKEOVER_DELAY_MS = 700L;
    private static final long PENDING_UNLOCK_TIMEOUT_MS = 60000L;
    private static final int MAX_UNLOCK_POLLS = 150;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Process logcatProcess;
    private Thread readerThread;
    private long readerReadyAt;
    private long lastPowerDown;
    private long lastLaunch;
    private WindowManager windowManager;
    private View overlayView;
    private PendingIntent pendingPostUnlockIntent;
    private long pendingPostUnlockExpiresAt;
    private boolean userPresentReceiverRegistered;

    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                handleUserPresent();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        addActivityLaunchOverlay();
        registerUserPresentReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_REAUTHORIZE_LOGS.equals(action)) {
            restartLogReader();
        } else if (!running) {
            startLogReader();
        }

        if (overlayView == null) {
            addActivityLaunchOverlay();
        }
        if (ACTION_TARGET_CHANGED.equals(action) || ACTION_REFRESH.equals(action)) {
            updateMonitorNotification();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (RuntimeException ignored) {
                // The window may already have been removed by the system.
            }
        }
        overlayView = null;
        pendingPostUnlockIntent = null;
        if (userPresentReceiverRegistered) {
            try {
                unregisterReceiver(userPresentReceiver);
            } catch (RuntimeException ignored) {
                // The process may already have detached the receiver.
            }
            userPresentReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void registerUserPresentReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(userPresentReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(userPresentReceiver, filter);
        }
        userPresentReceiverRegistered = true;
    }

    private void handleUserPresent() {
        final PendingIntent pendingIntent = pendingPostUnlockIntent;
        if (pendingIntent == null) {
            return;
        }
        pendingPostUnlockIntent = null;
        if (SystemClock.elapsedRealtime() > pendingPostUnlockExpiresAt) {
            Log.i(TAG, "Post-unlock target launch expired");
            return;
        }
        Log.i(TAG, "User-present received; opening selected app after ColorOS settles");
        mainHandler.postDelayed(
                () -> sendLaunchPendingIntent(pendingIntent),
                USER_PRESENT_LAUNCH_DELAY_MS);
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_monitor),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_monitor_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        NotificationChannel launchChannel = new NotificationChannel(
                LAUNCH_CHANNEL_ID,
                getString(R.string.notification_channel_launch),
                NotificationManager.IMPORTANCE_HIGH);
        launchChannel.setDescription(getString(R.string.notification_channel_launch_description));
        launchChannel.setShowBadge(false);
        launchChannel.setSound(null, null);
        launchChannel.enableVibration(false);
        launchChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(launchChannel);
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_REAUTHORIZE_LOGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String label = LaunchTargetStore.getSelectedLabel(this);
        String text = label == null
                ? getString(R.string.notification_no_target)
                : getString(R.string.notification_text, label);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder
                .setSmallIcon(R.drawable.ic_notification_power)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateMonitorNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void addActivityLaunchOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) {
            if (overlayView == null) {
                Log.w(TAG, "Display-over-other-apps permission is not enabled");
            }
            return;
        }
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlayView = new View(this);
            overlayView.setAlpha(0.01f);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlayView, params);
        } catch (RuntimeException exception) {
            overlayView = null;
            Log.w(TAG, "Could not add the activity-launch overlay", exception);
        }
    }

    private synchronized void startLogReader() {
        if (running) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_LOGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_LOGS has not been granted with ADB");
            return;
        }
        running = true;
        readerReadyAt = SystemClock.elapsedRealtime() + READER_WARM_UP_MS;
        readerThread = new Thread(this::readPowerKeyLog, "PowerButtonLogReader");
        readerThread.start();
        Log.i(TAG, "Power-button log reader started");
    }

    private synchronized void restartLogReader() {
        running = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        mainHandler.postDelayed(this::startLogReader, 400L);
    }

    private void readPowerKeyLog() {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "logcat",
                    "-b", "main",
                    "-b", "system",
                    "-v", "brief",
                    "-T", "1",
                    "KEYLOG_PhoneWindowManagerExtImpl:D",
                    "*:S");
            builder.redirectErrorStream(true);
            logcatProcess = builder.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(logcatProcess.getInputStream()));
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (isPhysicalPowerDown(line)) {
                    onPhysicalPowerDown();
                }
            }
        } catch (IOException exception) {
            Log.e(TAG, "The ColorOS power-key log could not be read", exception);
        } finally {
            if (logcatProcess != null) {
                logcatProcess.destroy();
                logcatProcess = null;
            }
            boolean shouldRestart = running;
            running = false;
            if (shouldRestart) {
                mainHandler.postDelayed(this::startLogReader, 2000L);
            }
        }
    }

    private boolean isPhysicalPowerDown(String line) {
        return line.contains("overrideInterceptKeyBeforeQueueing:")
                && line.contains("action=ACTION_DOWN")
                && line.contains("keyCode=KEYCODE_POWER")
                && line.contains("repeatCount=0")
                && !line.contains("deviceId=-1");
    }

    private synchronized void onPhysicalPowerDown() {
        long now = SystemClock.elapsedRealtime();
        if (now < readerReadyAt) {
            lastPowerDown = 0L;
            return;
        }
        long interval = now - lastPowerDown;
        lastPowerDown = now;
        if (interval < MIN_DOUBLE_PRESS_MS
                || interval > MAX_DOUBLE_PRESS_MS
                || now - lastLaunch < LAUNCH_COOLDOWN_MS) {
            return;
        }
        lastPowerDown = 0L;
        lastLaunch = now;
        Log.i(TAG, "Physical power-button double press detected (" + interval + " ms)");
        mainHandler.postDelayed(this::launchSelectedTarget, LAUNCH_DELAY_MS);
    }

    private void launchSelectedTarget() {
        Intent bridgeIntent = new Intent(this, PowerShortcutActivity.class)
                .setAction("com.antoine.chatgptpower.action.OPEN_SELECTED_APP")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        final PendingIntent bridgePendingIntent =
                createActivityPendingIntent(bridgeIntent, 1);

        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        boolean canUseFullScreenIntent = Build.VERSION.SDK_INT < 34
                || (notificationManager != null
                        && notificationManager.canUseFullScreenIntent());

        if (locked && notificationManager != null && canUseFullScreenIntent) {
            String label = LaunchTargetStore.getSelectedLabel(this);
            if (label == null) {
                label = getString(R.string.app_name);
            }
            Notification.Builder builder = new Notification.Builder(this, LAUNCH_CHANNEL_ID);
            Notification notification = builder
                    .setSmallIcon(R.drawable.ic_notification_power)
                    .setContentTitle(getString(R.string.locked_launch_title, label))
                    .setContentText(getString(R.string.locked_launch_text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(bridgePendingIntent)
                    .setFullScreenIntent(bridgePendingIntent, true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(15000L)
                    .build();
            Log.i(TAG, "Phone is locked; requesting a System UI full-screen launch");
            notificationManager.notify(LAUNCH_NOTIFICATION_ID, notification);

            final PendingIntent directTarget = createDirectTargetPendingIntent();
            pendingPostUnlockIntent = directTarget;
            pendingPostUnlockExpiresAt =
                    SystemClock.elapsedRealtime() + PENDING_UNLOCK_TIMEOUT_MS;
            schedulePostUnlockRelaunch(directTarget, 0);

            mainHandler.postDelayed(() -> {
                Log.i(TAG, "Trying Android 16 locked-screen PendingIntent takeover");
                sendLaunchPendingIntent(bridgePendingIntent);
            }, LOCKED_TAKEOVER_DELAY_MS);
            return;
        }

        if (locked) {
            Log.w(TAG, "Full-screen launch permission is unavailable; trying PendingIntent");
        }
        sendLaunchPendingIntent(bridgePendingIntent);
    }

    private PendingIntent createDirectTargetPendingIntent() {
        Intent intent = LaunchTargetStore.createLaunchIntent(this);
        if (intent == null) {
            intent = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_TARGET_MISSING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        return createActivityPendingIntent(intent, 2);
    }

    private PendingIntent createActivityPendingIntent(Intent intent, int requestCode) {
        ActivityOptions creatorOptions = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 36) {
            creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        } else if (Build.VERSION.SDK_INT >= 35) {
            creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                creatorOptions.toBundle());
    }

    private void schedulePostUnlockRelaunch(
            final PendingIntent pendingIntent,
            final int pollCount) {
        mainHandler.postDelayed(() -> {
            if (pendingPostUnlockIntent != pendingIntent) {
                return;
            }
            KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
            boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
            if (locked) {
                if (pollCount < MAX_UNLOCK_POLLS) {
                    schedulePostUnlockRelaunch(pendingIntent, pollCount + 1);
                } else {
                    Log.i(TAG, "Phone remained locked; cancelling post-unlock relaunch");
                    pendingPostUnlockIntent = null;
                }
                return;
            }

            mainHandler.postDelayed(() -> {
                if (pendingPostUnlockIntent != pendingIntent) {
                    return;
                }
                pendingPostUnlockIntent = null;
                Log.i(TAG, "Reasserting selected app after ColorOS unlock transition");
                sendLaunchPendingIntent(pendingIntent);
            }, UNLOCK_SETTLE_MS);
        }, UNLOCK_POLL_MS);
    }

    private void sendLaunchPendingIntent(PendingIntent pendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions senderOptions = ActivityOptions.makeBasic();
                senderOptions.setPendingIntentBackgroundActivityStartMode(
                        Build.VERSION.SDK_INT >= 36
                                ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                                : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pendingIntent.send(senderOptions.toBundle());
            } else {
                pendingIntent.send();
            }
        } catch (PendingIntent.CanceledException | RuntimeException exception) {
            Log.e(TAG, "Could not open the selected app after the double press", exception);
        }
    }
}
