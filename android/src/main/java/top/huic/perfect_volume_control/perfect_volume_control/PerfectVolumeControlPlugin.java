package top.huic.perfect_volume_control.perfect_volume_control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * PerfectVolumeControlPlugin
 */
public class PerfectVolumeControlPlugin
        implements FlutterPlugin, ActivityAware, MethodCallHandler, DefaultLifecycleObserver {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private MethodChannel channel;

    private Context applicationContext;

    /**
     * 音频管理器
     */
    private AudioManager audioManager;

    /**
     * 隐藏系统音量图标
     */
    private Boolean hideUI = false;

    /**
     * Registered only while the host activity is started. VOLUME_CHANGED_ACTION
     * is a frequent non-ordered broadcast; on Android 13 the system keeps
     * sending it to a frozen cached process until its binder buffer fills, then
     * kills the app with CannotDeliverBroadcastException. Engines without an
     * activity (WorkManager, downloader callbacks) never register one.
     */
    @Nullable
    private VolumeReceiver volumeReceiver;

    @Nullable
    private Lifecycle activityLifecycle;

    /**
     * Last volume handed to Dart, used to resync after changes made while the
     * receiver was unregistered. Null until Dart first reads the volume.
     */
    @Nullable
    private Double lastVolume;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "perfect_volume_control");
        channel.setMethodCallHandler(this);
        applicationContext = flutterPluginBinding.getApplicationContext();
        audioManager = (AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityLifecycle = ((HiddenLifecycleReference) binding.getLifecycle()).getLifecycle();
        activityLifecycle.addObserver(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityLifecycle != null) {
            activityLifecycle.removeObserver(this);
            activityLifecycle = null;
        }
        unregisterVolumeReceiver();
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        Double previous = lastVolume;
        registerVolumeReceiver();
        if (previous != null) {
            double current = readVolume();
            if (current != previous) {
                channel.invokeMethod("volumeChangeListener", current);
            }
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        unregisterVolumeReceiver();
    }

    private void registerVolumeReceiver() {
        if (volumeReceiver != null) {
            return;
        }
        // 注册音量监听
        volumeReceiver = new VolumeReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        applicationContext.registerReceiver(volumeReceiver, filter);
    }

    private void unregisterVolumeReceiver() {
        if (volumeReceiver == null) {
            return;
        }
        applicationContext.unregisterReceiver(volumeReceiver);
        volumeReceiver = null;
    }

    private double readVolume() {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastVolume = (double) current / (double) max;
        return lastVolume;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "getVolume":
                this.getVolume(call, result);
                break;
            case "setVolume":
                this.setVolume(call, result);
                break;
            case "hideUI":
                this.hideUI(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        unregisterVolumeReceiver();
    }

    /**
     * 获得音量
     */
    public void getVolume(@NonNull MethodCall call, @NonNull Result result) {
        result.success(readVolume());
    }

    /**
     * 设置音量
     */
    public void setVolume(@NonNull MethodCall call, @NonNull Result result) {
        Double volume = call.argument("volume");
        if (volume == null) {
            result.error("-1", "Volume cannot be empty", "Volume cannot be empty");
            return;
        }
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(max * volume), hideUI ? AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE : AudioManager.FLAG_SHOW_UI);
        result.success(null);
    }

    /**
     * 隐藏UI
     */
    public void hideUI(@NonNull MethodCall call, @NonNull Result result) {
        this.hideUI = call.argument("hide");
        result.success(null);
    }

    /**
     * 音量监听
     */
    private class VolumeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.media.VOLUME_CHANGED_ACTION")) {
                channel.invokeMethod("volumeChangeListener", readVolume());
            }
        }
    }
}
