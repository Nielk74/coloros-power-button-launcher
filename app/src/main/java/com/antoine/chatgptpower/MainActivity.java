package com.antoine.chatgptpower;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** App picker and setup dashboard for the ColorOS power-button shortcut. */
public final class MainActivity extends Activity {
    public static final String EXTRA_REAUTHORIZE_LOGS = "reauthorize_logs";
    public static final String EXTRA_TARGET_MISSING = "target_missing";
    private static final int REQUEST_NOTIFICATIONS = 2604;

    private ListView appList;
    private AppAdapter adapter;
    private ImageView selectedIcon;
    private TextView selectedName;
    private TextView selectedPackage;
    private TextView statusBadge;
    private TextView statusDescription;
    private TextView setupTitle;
    private TextView setupDetails;
    private TextView emptyMessage;
    private LinearLayout setupCard;
    private Button testButton;
    private Button setupActionButton;
    private EditText searchApps;
    private SetupAction setupAction = SetupAction.NONE;
    private final List<AppEntry> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        bindViews();
        LaunchTargetStore.initializeDefaultTarget(this);
        configureList();
        configureActions();
        consumeIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startMonitor(false);
        refreshScreen();
    }

    private void applySystemBarInsets() {
        final View root = findViewById(R.id.app_list);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            int extraBottom = (int) (24 * getResources().getDisplayMetrics().density);
            view.setPadding(0, bars.top, 0, bars.bottom + extraBottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void bindViews() {
        appList = findViewById(R.id.app_list);
        View header = getLayoutInflater().inflate(R.layout.header_main, appList, false);
        appList.addHeaderView(header, null, false);

        selectedIcon = header.findViewById(R.id.selected_icon);
        selectedName = header.findViewById(R.id.selected_name);
        selectedPackage = header.findViewById(R.id.selected_package);
        statusBadge = header.findViewById(R.id.status_badge);
        statusDescription = header.findViewById(R.id.status_description);
        setupTitle = header.findViewById(R.id.setup_title);
        setupDetails = header.findViewById(R.id.setup_details);
        emptyMessage = header.findViewById(R.id.empty_message);
        setupCard = header.findViewById(R.id.setup_card);
        testButton = header.findViewById(R.id.test_button);
        setupActionButton = header.findViewById(R.id.setup_action_button);
        searchApps = header.findViewById(R.id.search_apps);
    }

    private void configureList() {
        adapter = new AppAdapter();
        appList.setAdapter(adapter);
        searchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                adapter.filter(value == null ? "" : value.toString());
                updateEmptyMessage();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
    }

    private void configureActions() {
        testButton.setOnClickListener(view -> openSelectedApp());
        findViewById(R.id.reauthorize_button).setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            startMonitor(true);
            Toast.makeText(this, R.string.monitor_reauthorized, Toast.LENGTH_LONG).show();
        });
        setupActionButton.setOnClickListener(view -> performSetupAction());
    }

    private void consumeIntent(Intent intent) {
        if (intent == null) {
            startMonitor(false);
            return;
        }
        boolean reauthorize = intent.getBooleanExtra(EXTRA_REAUTHORIZE_LOGS, false);
        boolean targetMissing = intent.getBooleanExtra(EXTRA_TARGET_MISSING, false);
        intent.removeExtra(EXTRA_REAUTHORIZE_LOGS);
        intent.removeExtra(EXTRA_TARGET_MISSING);
        startMonitor(reauthorize);
        if (targetMissing) {
            Toast.makeText(this, R.string.target_missing_toast, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshScreen() {
        discoverApps();
        adapter.setApps(apps, LaunchTargetStore.getSelectedPackage(this));
        adapter.filter(searchApps.getText() == null ? "" : searchApps.getText().toString());
        renderSelectedTarget();
        renderSetup();
        updateEmptyMessage();
    }

    private void discoverApps() {
        apps.clear();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> matches = getPackageManager().queryIntentActivities(
                launcherIntent,
                PackageManager.MATCH_ALL);

        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        for (ResolveInfo match : matches) {
            if (match.activityInfo == null
                    || !match.activityInfo.exported
                    || !match.activityInfo.enabled
                    || getPackageName().equals(match.activityInfo.packageName)) {
                continue;
            }
            byPackage.putIfAbsent(match.activityInfo.packageName, match);
        }

        for (Map.Entry<String, ResolveInfo> item : byPackage.entrySet()) {
            CharSequence loadedLabel = item.getValue().loadLabel(getPackageManager());
            String label = loadedLabel == null ? item.getKey() : loadedLabel.toString();
            apps.add(new AppEntry(item.getKey(), label, item.getValue()));
        }

        Collator collator = Collator.getInstance();
        Collections.sort(apps, (left, right) -> {
            int labelOrder = collator.compare(left.label, right.label);
            return labelOrder != 0 ? labelOrder : left.packageName.compareTo(right.packageName);
        });
    }

    private void renderSelectedTarget() {
        String packageName = LaunchTargetStore.getSelectedPackage(this);
        String label = LaunchTargetStore.getSelectedLabel(this);
        Intent launchIntent = LaunchTargetStore.createLaunchIntent(this);
        boolean available = launchIntent != null;

        if (packageName == null) {
            selectedIcon.setImageResource(R.drawable.ic_power_launcher);
            selectedName.setText(R.string.no_app_selected);
            selectedPackage.setText(R.string.choose_below);
        } else {
            selectedName.setText(available
                    ? label
                    : getString(R.string.target_unavailable, label));
            selectedPackage.setText(packageName);
            selectedIcon.setImageDrawable(loadApplicationIcon(packageName));
        }

        testButton.setEnabled(available);
        testButton.setAlpha(available ? 1f : 0.45f);
    }

    private void renderSetup() {
        List<Integer> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_LOGS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(R.string.missing_read_logs);
        }
        if (!Settings.canDrawOverlays(this)) {
            missing.add(R.string.missing_overlay);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            missing.add(R.string.missing_notifications);
        }
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 34
                && notificationManager != null
                && !notificationManager.canUseFullScreenIntent()) {
            missing.add(R.string.missing_full_screen);
        }

        String packageName = LaunchTargetStore.getSelectedPackage(this);
        String label = LaunchTargetStore.getSelectedLabel(this);
        boolean targetAvailable = LaunchTargetStore.isSelectedTargetAvailable(this);

        if (packageName == null || !targetAvailable) {
            statusBadge.setText(R.string.status_no_target);
            statusBadge.setTextColor(getColor(R.color.badge_text));
            statusDescription.setText(R.string.status_no_target_description);
        } else if (missing.isEmpty()) {
            statusBadge.setText(R.string.status_ready);
            statusBadge.setTextColor(getColor(R.color.success));
            statusDescription.setText(getString(R.string.status_ready_description, label));
        } else {
            statusBadge.setText(R.string.status_setup);
            statusBadge.setTextColor(getColor(R.color.badge_text));
            statusDescription.setText(R.string.status_setup_description);
        }

        if (missing.isEmpty()) {
            setupCard.setVisibility(View.GONE);
            setupAction = SetupAction.NONE;
            return;
        }

        setupCard.setVisibility(View.VISIBLE);
        setupTitle.setText(missing.size() == 1
                ? R.string.setup_title
                : R.string.setup_multiple_title);
        StringBuilder detailText = new StringBuilder();
        for (int index = 0; index < missing.size(); index++) {
            if (index > 0) {
                detailText.append('\n');
            }
            detailText.append("• ").append(getString(missing.get(index)));
        }
        setupDetails.setText(detailText.toString());

        int first = missing.get(0);
        if (first == R.string.missing_read_logs) {
            setupAction = SetupAction.COPY_ADB_COMMAND;
            setupActionButton.setText(R.string.copy_adb_command);
        } else if (first == R.string.missing_overlay) {
            setupAction = SetupAction.OPEN_OVERLAY_SETTINGS;
            setupActionButton.setText(R.string.open_overlay_settings);
        } else if (first == R.string.missing_notifications) {
            setupAction = SetupAction.REQUEST_NOTIFICATIONS;
            setupActionButton.setText(R.string.allow_notifications);
        } else {
            setupAction = SetupAction.OPEN_FULL_SCREEN_SETTINGS;
            setupActionButton.setText(R.string.open_full_screen_settings);
        }
    }

    private void performSetupAction() {
        setupActionButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        try {
            switch (setupAction) {
                case COPY_ADB_COMMAND:
                    copyAdbCommand();
                    break;
                case OPEN_OVERLAY_SETTINGS:
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    break;
                case REQUEST_NOTIFICATIONS:
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                REQUEST_NOTIFICATIONS);
                    }
                    break;
                case OPEN_FULL_SCREEN_SETTINGS:
                    if (Build.VERSION.SDK_INT >= 34) {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:" + getPackageName())));
                    }
                    break;
                case NONE:
                    break;
            }
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void copyAdbCommand() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.copy_adb_command),
                getString(R.string.adb_grant_command)));
        Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show();
    }

    private void selectApp(AppEntry app, View source) {
        LaunchTargetStore.setTarget(this, app.packageName, app.label);
        source.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        adapter.setSelectedPackage(app.packageName);
        renderSelectedTarget();
        renderSetup();
        startMonitorWithAction(PowerButtonMonitorService.ACTION_TARGET_CHANGED);
        Toast.makeText(
                this,
                getString(R.string.app_selected_toast, app.label),
                Toast.LENGTH_SHORT).show();
    }

    private void openSelectedApp() {
        Intent launchIntent = LaunchTargetStore.createLaunchIntent(this);
        if (launchIntent == null) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(launchIntent);
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void startMonitor(boolean reauthorize) {
        startMonitorWithAction(reauthorize
                ? PowerButtonMonitorService.ACTION_REAUTHORIZE_LOGS
                : PowerButtonMonitorService.ACTION_REFRESH);
    }

    private void startMonitorWithAction(String action) {
        Intent monitor = new Intent(this, PowerButtonMonitorService.class).setAction(action);
        try {
            startForegroundService(monitor);
        } catch (RuntimeException ignored) {
            // The setup card and persistent notification make failures visible to the user.
        }
    }

    private Drawable loadApplicationIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return getDrawable(R.drawable.ic_power_launcher);
        }
    }

    private void updateEmptyMessage() {
        emptyMessage.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private enum SetupAction {
        NONE,
        COPY_ADB_COMMAND,
        OPEN_OVERLAY_SETTINGS,
        REQUEST_NOTIFICATIONS,
        OPEN_FULL_SCREEN_SETTINGS
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final ResolveInfo resolveInfo;

        AppEntry(String packageName, String label, ResolveInfo resolveInfo) {
            this.packageName = packageName;
            this.label = label;
            this.resolveInfo = resolveInfo;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> allApps = new ArrayList<>();
        private final List<AppEntry> visibleApps = new ArrayList<>();
        private final LruCache<String, Drawable> icons = new LruCache<>(48);
        private String selectedPackageName;
        private String query = "";

        void setApps(List<AppEntry> newApps, String selectedPackage) {
            allApps.clear();
            allApps.addAll(newApps);
            selectedPackageName = selectedPackage;
        }

        void setSelectedPackage(String packageName) {
            selectedPackageName = packageName;
            notifyDataSetChanged();
        }

        void filter(String newQuery) {
            query = newQuery == null ? "" : newQuery.trim().toLowerCase(Locale.ROOT);
            visibleApps.clear();
            for (AppEntry app : allApps) {
                if (query.isEmpty()
                        || app.label.toLowerCase(Locale.ROOT).contains(query)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                    visibleApps.add(app);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return visibleApps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return visibleApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).packageName.hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            ViewHolder holder;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_app, parent, false);
                holder = new ViewHolder(row);
                row.setTag(holder);
            } else {
                holder = (ViewHolder) row.getTag();
            }

            AppEntry app = getItem(position);
            boolean selected = app.packageName.equals(selectedPackageName);
            Drawable icon = icons.get(app.packageName);
            if (icon == null) {
                try {
                    icon = app.resolveInfo.loadIcon(getPackageManager());
                } catch (RuntimeException ignored) {
                    icon = getDrawable(R.drawable.ic_power_launcher);
                }
                icons.put(app.packageName, icon);
            }

            holder.icon.setImageDrawable(icon);
            holder.name.setText(app.label);
            holder.packageName.setText(app.packageName);
            holder.badge.setVisibility(selected ? View.VISIBLE : View.GONE);
            holder.radio.setChecked(selected);
            holder.card.setSelected(selected);
            holder.card.setContentDescription(getString(
                    selected
                            ? R.string.app_selected_accessibility
                            : R.string.app_accessibility,
                    app.label,
                    app.packageName));
            holder.card.setOnClickListener(view -> selectApp(app, view));
            return row;
        }
    }

    private static final class ViewHolder {
        final View card;
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView badge;
        final RadioButton radio;

        ViewHolder(View row) {
            card = row.findViewById(R.id.app_card);
            icon = row.findViewById(R.id.app_icon);
            name = row.findViewById(R.id.app_name);
            packageName = row.findViewById(R.id.app_package);
            badge = row.findViewById(R.id.selected_badge);
            radio = row.findViewById(R.id.app_radio);
        }
    }
}
