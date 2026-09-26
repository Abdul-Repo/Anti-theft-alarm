package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AlarmService extends Service {
    private static final String TAG = "AlarmService";
    private static final String CHANNEL_ID = "AlarmServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int VOLUME_CHECK_INTERVAL_MS = 500; // Enforce full volume every 0.5 seconds as requested
    private static final int FLASH_INTERVAL_MS = 500;

    private MediaPlayer mediaPlayer;
    private boolean isAlarmTriggered = false;
    private PowerManager.WakeLock wakeLock;
    private int selectedSoundResId = R.raw.siren1;
    private String customSoundUriString = null;
    private boolean isFlashEnabled = true;
    
    private Handler taskHandler;
    private Runnable volumeEnforcerRunnable;
    private Runnable flashRunnable;
    private boolean flashOn = false;
    private String cameraId;
    private CameraManager cameraManager;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                boolean isPlugged = (plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                     plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                                     plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS);
                
                if (!isPlugged && !isAlarmTriggered) {
                    triggerAlarm();
                }
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                if (!isAlarmTriggered) {
                    triggerAlarm();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        taskHandler = new Handler(Looper.getMainLooper());
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null && cameraManager.getCameraIdList().length > 0) {
                cameraId = cameraManager.getCameraIdList()[0];
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not access camera for flash", e);
        }
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiTheft:AlarmWakelock");
            wakeLock.acquire();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("sound_res_id")) {
                selectedSoundResId = intent.getIntExtra("sound_res_id", R.raw.siren1);
            }
            customSoundUriString = intent.getStringExtra("custom_sound_uri");
            isFlashEnabled = intent.getBooleanExtra("flash_active", true);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Anti-Theft Active")
                .setContentText("Monitoring power connection...")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_STICKY;
    }

    private void triggerAlarm() {
        if (isAlarmTriggered) return;
        isAlarmTriggered = true;

        enforceMaxVolume();
        startLoops();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int sessionId = audioManager != null ? audioManager.generateAudioSessionId() : 0;

        try {
            if (customSoundUriString != null) {
                Uri uri = Uri.parse(customSoundUriString);
                mediaPlayer = MediaPlayer.create(this, uri, null, audioAttributes, sessionId);
            } else {
                mediaPlayer = MediaPlayer.create(this, selectedSoundResId, audioAttributes, sessionId);
            }

            if (mediaPlayer == null) {
                Log.w(TAG, "Selected sound failed to load, falling back to default music.");
                mediaPlayer = MediaPlayer.create(this, R.raw.siren1, audioAttributes, sessionId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating MediaPlayer with custom music, trying default music fallback", e);
            try {
                mediaPlayer = MediaPlayer.create(this, R.raw.siren1, audioAttributes, sessionId);
            } catch (Exception ex) {
                Log.e(TAG, "Error creating fallback default MediaPlayer", ex);
            }
        }

        if (mediaPlayer != null) {
            try {
                mediaPlayer.setLooping(true);
                mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                mediaPlayer.start();
            } catch (Exception e) {
                Log.e(TAG, "Error starting music playback", e);
            }
        }
    }

    private void enforceMaxVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);
        }
    }

    private void startLoops() {
        // Volume Loop - enforces full volume every 0.5 seconds as requested
        volumeEnforcerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAlarmTriggered) {
                    enforceMaxVolume();
                    taskHandler.postDelayed(this, VOLUME_CHECK_INTERVAL_MS);
                }
            }
        };
        taskHandler.post(volumeEnforcerRunnable);

        // Flash Loop
        if (cameraId != null && isFlashEnabled) {
            flashRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isAlarmTriggered) {
                        try {
                            flashOn = !flashOn;
                            cameraManager.setTorchMode(cameraId, flashOn);
                        } catch (Exception e) {
                            Log.e(TAG, "Flash error", e);
                        }
                        taskHandler.postDelayed(this, FLASH_INTERVAL_MS);
                    }
                }
            };
            taskHandler.post(flashRunnable);
        }
    }

    @Override
    public void onDestroy() {
        isAlarmTriggered = false;
        if (taskHandler != null) {
            taskHandler.removeCallbacksAndMessages(null);
        }
        
        // Turn off flash if it was left on
        if (cameraId != null && flashOn) {
            try {
                cameraManager.setTorchMode(cameraId, false);
            } catch (Exception ignored) {}
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        try {
            unregisterReceiver(powerReceiver);
        } catch (Exception ignored) {}
        
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Anti-Theft Service", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}
