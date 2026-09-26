package com.example.myapplication;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private MaterialButton activateButton;
    private Spinner soundSpinner;
    private TextView batteryPercentageText;
    private MaterialSwitch flashSwitch;
    private MaterialButton aboutButton;
    private MaterialCardView aboutCard;
    private MaterialButton closeAboutButton;

    private boolean isAlarmActive = false;
    private boolean isDevicePlugged = false;
    private int selectedSoundResId = R.raw.siren1;

    private ObjectAnimator pulseAnimator;

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "AntiTheftPrefs";
    private static final String KEY_ALARM_ACTIVE = "isAlarmActive";
    private static final String KEY_FLASH_ACTIVE = "isFlashActive";
    private static final String KEY_CUSTOM_SOUND_URI = "customSoundUri";
    private static final String KEY_CUSTOM_SOUND_NAME = "customSoundName";
    private static final String KEY_SELECTED_SOUND_TYPE = "selectedSoundType";

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private List<SoundOption> spinnerOptions = new ArrayList<>();
    private ArrayAdapter<SoundOption> spinnerAdapter;
    private int lastSelectedPosition = 0;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int pct = (int) ((level / (float) scale) * 100);
            batteryPercentageText.setText("Battery: " + pct + "%");

            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            isDevicePlugged = (plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS);

            updateUiState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        statusText = findViewById(R.id.StatusText);
        activateButton = findViewById(R.id.ActivateButton);
        soundSpinner = findViewById(R.id.SoundSpinner);
        batteryPercentageText = findViewById(R.id.BatteryPercentage);
        flashSwitch = findViewById(R.id.FlashSwitch);
        aboutButton = findViewById(R.id.AboutButton);
        aboutCard = findViewById(R.id.AboutCard);
        closeAboutButton = findViewById(R.id.CloseAboutButton);

        // Load persisted alarm state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isAlarmActive = prefs.getBoolean(KEY_ALARM_ACTIVE, false);
        boolean isFlashActive = prefs.getBoolean(KEY_FLASH_ACTIVE, true);
        flashSwitch.setChecked(isFlashActive);

        flashSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_FLASH_ACTIVE, isChecked).apply();
        });

        setupSoundSpinner();
        checkPermissions();
        setupAnimation();
        setupAboutSection();

        activateButton.setOnClickListener(v -> {
            if (!isDevicePlugged && !isAlarmActive) {
                Toast.makeText(this, R.string.plug_in_to_activate, Toast.LENGTH_LONG).show();
                return;
            }

            if (isAlarmActive) {
                deactivateAlarm();
            } else {
                activateAlarm();
            }
        });

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void setupAboutSection() {
        if (aboutButton != null && aboutCard != null) {
            aboutButton.setOnClickListener(v -> {
                if (aboutCard.getVisibility() == View.VISIBLE) {
                    aboutCard.setVisibility(View.GONE);
                } else {
                    aboutCard.setVisibility(View.VISIBLE);
                }
            });
        }
        if (closeAboutButton != null && aboutCard != null) {
            closeAboutButton.setOnClickListener(v -> aboutCard.setVisibility(View.GONE));
        }
    }

    private void setupAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                activateButton,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
        );
        pulseAnimator.setDuration(1000);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
    }

    private void setupSoundSpinner() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            try {
                                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception e) {
                                Log.e("MainActivity", "Failed to take persistable URI permission", e);
                            }
                            
                            String fileName = getFileName(uri);
                            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            prefs.edit()
                                    .putString(KEY_CUSTOM_SOUND_URI, uri.toString())
                                    .putString(KEY_CUSTOM_SOUND_NAME, fileName)
                                    .putString(KEY_SELECTED_SOUND_TYPE, "custom")
                                    .apply();

                            rebuildSpinnerOptions();
                            soundSpinner.setSelection(1);
                            lastSelectedPosition = 1;
                        }
                    } else {
                        soundSpinner.setSelection(lastSelectedPosition);
                    }
                }
        );

        rebuildSpinnerOptions();

        spinnerAdapter = new ArrayAdapter<SoundOption>(this, android.R.layout.simple_spinner_item, spinnerOptions) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.card_bg));
                view.setTextColor(Color.WHITE);
                view.setPadding(32, 32, 32, 32);
                return view;
            }
        };
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        soundSpinner.setAdapter(spinnerAdapter);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String selectedType = prefs.getString(KEY_SELECTED_SOUND_TYPE, "default");
        if ("custom".equals(selectedType) && spinnerOptions.size() > 2) {
            soundSpinner.setSelection(1);
            lastSelectedPosition = 1;
        } else {
            soundSpinner.setSelection(0);
            lastSelectedPosition = 0;
        }

        soundSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SoundOption selectedOption = spinnerOptions.get(position);
                if (selectedOption.isLink) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");
                    filePickerLauncher.launch(intent);
                } else {
                    lastSelectedPosition = position;
                    SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                    if (position == 0) {
                        selectedSoundResId = selectedOption.resId;
                        editor.putString(KEY_SELECTED_SOUND_TYPE, "default");
                    } else {
                        editor.putString(KEY_SELECTED_SOUND_TYPE, "custom");
                    }
                    editor.apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void rebuildSpinnerOptions() {
        spinnerOptions.clear();
        spinnerOptions.add(new SoundOption("Classic Siren", R.raw.siren1));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String customUri = prefs.getString(KEY_CUSTOM_SOUND_URI, null);
        String customName = prefs.getString(KEY_CUSTOM_SOUND_NAME, "Custom Sound");

        if (customUri != null) {
            spinnerOptions.add(new SoundOption(customName, customUri));
        }

        spinnerOptions.add(SoundOption.createLink("Select from file explorer..."));
        
        if (spinnerAdapter != null) {
            spinnerAdapter.notifyDataSetChanged();
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e("MainActivity", "Failed to get file name", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void updateUiState() {
        if (isAlarmActive) {
            activateButton.setText(R.string.activated);
            activateButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.neon_green)));
            statusText.setText(R.string.status_active);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.neon_green));
            soundSpinner.setEnabled(false);
            flashSwitch.setEnabled(false);
            if (!pulseAnimator.isRunning()) {
                pulseAnimator.start();
            }
        } else {
            activateButton.setText(R.string.activate);
            activateButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.neon_red)));
            soundSpinner.setEnabled(true);
            flashSwitch.setEnabled(true);
            if (pulseAnimator.isRunning()) {
                pulseAnimator.cancel();
                activateButton.setScaleX(1f);
                activateButton.setScaleY(1f);
            }
            
            if (isDevicePlugged) {
                statusText.setText(R.string.status_ready);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.neon_blue));
            } else {
                statusText.setText(R.string.status_not_plugged);
                statusText.setTextColor(Color.WHITE);
            }
        }
    }

    private void activateAlarm() {
        isAlarmActive = true;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALARM_ACTIVE, true)
                .apply();
        updateUiState();
        
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.putExtra("sound_res_id", selectedSoundResId);
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String selectedType = prefs.getString(KEY_SELECTED_SOUND_TYPE, "default");
        if ("custom".equals(selectedType)) {
            String customUri = prefs.getString(KEY_CUSTOM_SOUND_URI, null);
            serviceIntent.putExtra("custom_sound_uri", customUri);
        }
        
        serviceIntent.putExtra("flash_active", flashSwitch.isChecked());
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void deactivateAlarm() {
        isAlarmActive = false;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALARM_ACTIVE, false)
                .apply();
        updateUiState();
        
        Intent serviceIntent = new Intent(this, AlarmService.class);
        stopService(serviceIntent);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            // ignore
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
    }

    private static class SoundOption {
        String name;
        int resId;
        String uriString;
        boolean isLink;

        SoundOption(String name, int resId) {
            this.name = name;
            this.resId = resId;
            this.uriString = null;
            this.isLink = false;
        }

        SoundOption(String name, String uriString) {
            this.name = name;
            this.resId = -1;
            this.uriString = uriString;
            this.isLink = false;
        }

        static SoundOption createLink(String name) {
            SoundOption option = new SoundOption(name, -1);
            option.isLink = true;
            return option;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }
}
