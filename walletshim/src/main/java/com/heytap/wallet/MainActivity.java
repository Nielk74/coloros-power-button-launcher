package com.heytap.wallet;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;
import android.widget.Toast;

/**
 * Minimal bridge for ColorOS firmware that hard-codes {@code com.heytap.wallet}
 * as its double-power-button Wallet target.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "WalletShim";
    private static final String GOOGLE_WALLET_PACKAGE =
            "com.google.android.apps.walletnfcrel";
    private static final String GOOGLE_WALLET_QUICKDRAW_ACTION =
            "com.google.android.apps.wallet.main.QUICKDRAW";

    private CancellationSignal cancellationSignal;
    private boolean authenticationStarted;
    private boolean launchStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        Log.i(TAG, "Received ColorOS wallet launch: action=" + getIntent().getAction());
        AccessibilityStateRepair.restoreIfAuthorized(this, "shim activity launch");
        if (savedInstanceState == null) {
            openWalletRespectingDeviceLock();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(TAG, "Received another ColorOS wallet launch: action=" + intent.getAction());
        if (!authenticationStarted && !launchStarted) {
            openWalletRespectingDeviceLock();
        }
    }

    private void openWalletRespectingDeviceLock() {
        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        if (keyguardManager != null && !keyguardManager.isDeviceLocked()) {
            Log.i(TAG, "Device is already unlocked; opening Google Wallet directly");
            openGoogleWallet();
            return;
        }
        authenticateThenOpenWallet();
    }

    private void authenticateThenOpenWallet() {
        if (authenticationStarted || launchStarted || isFinishing()) {
            return;
        }
        authenticationStarted = true;

        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.authentication_title))
                    .setDescription(getString(R.string.authentication_description))
                    .setConfirmationRequired(false)
                    .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG
                                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            cancellationSignal = new CancellationSignal();
            prompt.authenticate(
                    cancellationSignal,
                    getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            authenticationStarted = false;
                            Log.i(TAG, "Authentication succeeded; opening Google Wallet");
                            openGoogleWallet();
                        }

                        @Override
                        public void onAuthenticationError(
                                int errorCode,
                                CharSequence errString) {
                            authenticationStarted = false;
                            Log.i(TAG, "Authentication ended: code=" + errorCode);
                            finishAndRemoveTask();
                        }
                    });
        } catch (RuntimeException exception) {
            authenticationStarted = false;
            Log.e(TAG, "Unable to start system authentication", exception);
            Toast.makeText(
                            this,
                            R.string.authentication_unavailable,
                            Toast.LENGTH_LONG)
                    .show();
            finishAndRemoveTask();
        }
    }

    private void openGoogleWallet() {
        if (launchStarted || isFinishing()) {
            return;
        }
        launchStarted = true;

        Intent quickDrawIntent = new Intent(GOOGLE_WALLET_QUICKDRAW_ACTION)
                .setPackage(GOOGLE_WALLET_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(quickDrawIntent);
            Log.i(TAG, "Google Wallet QUICKDRAW activity started");
            finishAndRemoveTask();
            return;
        } catch (ActivityNotFoundException exception) {
            Log.w(TAG, "Google Wallet QUICKDRAW action unavailable; using launcher fallback");
        } catch (SecurityException exception) {
            Log.w(TAG, "Google Wallet QUICKDRAW action rejected; using launcher fallback");
        }

        Intent launcherIntent = getPackageManager()
                .getLaunchIntentForPackage(GOOGLE_WALLET_PACKAGE);
        if (launcherIntent == null) {
            Log.e(TAG, "Google Wallet is not installed");
            Toast.makeText(this, R.string.wallet_not_installed, Toast.LENGTH_LONG).show();
            finishAndRemoveTask();
            return;
        }

        try {
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launcherIntent);
            Log.i(TAG, "Google Wallet launcher activity started");
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(TAG, "Unable to open Google Wallet", exception);
            Toast.makeText(this, R.string.wallet_launch_failed, Toast.LENGTH_LONG).show();
        }
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        if (cancellationSignal != null && !cancellationSignal.isCanceled()) {
            cancellationSignal.cancel();
        }
        cancellationSignal = null;
        super.onDestroy();
    }
}
