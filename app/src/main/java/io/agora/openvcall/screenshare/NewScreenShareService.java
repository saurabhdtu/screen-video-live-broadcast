package io.agora.openvcall.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;

import java.io.IOException;

import io.agora.openvcall.AGApplication;

public class NewScreenShareService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "ExternalVideo";
    private static final int DISPLAY_WIDTH = 720;
    private static final int DISPLAY_HEIGHT = 1280;
    AGApplication agoraApplication;
    ScreenShareBinder screenShareBinder;
    MediaProjectionManager mpm;
    MediaProjectionCallback mediaProjectionCallback;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay2;
    private MediaProjection mediaProjection;
    private ScreenInputManager screenInputManager;

    @Override
    public void onCreate() {
        super.onCreate();
//        mediaRecorder = new MediaRecorder();
        screenInputManager = new ScreenInputManager((AGApplication) getApplication());
        agoraApplication = (AGApplication) getApplication();
        screenShareBinder = new ScreenShareBinder();
        mediaProjectionCallback = new MediaProjectionCallback();
        agoraApplication.rtcEngine().addPublishStreamUrl("rtmp://18.141.113.171:1935/destA/instA", true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return screenShareBinder;
    }

    private void startForeground() {
        Intent notificationIntent = new Intent(getApplicationContext(),
                getApplicationContext().getClass());
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, 0);

        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle(CHANNEL_ID)
                .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
            builder.setChannelId(CHANNEL_ID);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_ID, importance);
            channel.setDescription(CHANNEL_ID);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void startSourceManager() {
        screenInputManager.start();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (screenInputManager != null) {
            screenInputManager.stop();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        stopForeground(true);
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();
        /*mpm = (MediaProjectionManager) getSystemService
                (Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(-1, intent);
        mediaProjection.registerCallback(mediaProjectionCallback, null);*/
//        initRecorder();
//        prepareRecorder();
//        setMediaProjection(mediaProjection);
//        mediaRecorder.start();
//        setInput(mediaProjection, intent);
        startSourceManager();
        return START_STICKY;
    }

    public void setInput(MediaProjection mediaProjection, Intent data) {
        screenInputManager.setExternalVideoInput(mediaProjection, data);
    }

    private void stopScreenSharing() {
        if (virtualDisplay2 == null) {
            return;
        }
        virtualDisplay2.release();
        virtualDisplay2.release();
        if (mediaRecorder != null) {
//            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
        }
    }

    private void prepareRecorder() {
        try {
            mediaRecorder.prepare();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initRecorder() {
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mediaRecorder.setVideoEncodingBitRate(512 * 1000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mediaRecorder.setOutputFile("/sdcard/capture.mp4");
    }

    public void setMediaProjection(MediaProjection mediaProjection) {
        try {
            virtualDisplay2 = mediaProjection.createVirtualDisplay("v2",
                    DISPLAY_WIDTH, DISPLAY_HEIGHT, 3,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            /*initRecorder();
            prepareRecorder();*/
            mediaProjection = null;
            stopScreenSharing();
        }
    }

    public class ScreenShareBinder extends Binder {
        public NewScreenShareService getService() {
            return NewScreenShareService.this;
        }

    }
}
