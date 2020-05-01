package io.agora.openvcall;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import io.agora.openvcall.model.ConstantApp;
import io.agora.openvcall.model.MyEngineEventHandler;
import io.agora.openvcall.ui.BaseActivity;
import io.agora.rtc.Constants;
import io.agora.rtc.video.VideoEncoderConfiguration;


public class SwitchVideoInputActivity extends BaseActivity {
    private static final String TAG = SwitchVideoInputActivity.class.getSimpleName();
    private static final String VIDEO_NAME = "localVideo.mp4";
    private static final int PROJECTION_REQ_CODE = 1 << 2;
    private static final int DEFAULT_SHARE_FRAME_RATE = 15;

    // The developers should defines their video dimension, for the
    // video info cannot be obtained before the video is extracted.

    private static final int DISPLAY_WIDTH = 480;
    private static final int DISPLAY_HEIGHT = 640;
    MediaProjectionManager mpm;
    boolean start = false;
    MyEngineEventHandler agEventHandler;
    private ScreenShareService.ScreenShareBinder mService;
    private VideoInputServiceConnection mServiceConnection;


    private void bindVideoService(Intent intent) {
        intent.setClass(this, ScreenShareService.class);
        mServiceConnection = new VideoInputServiceConnection();
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        ContextCompat.startForegroundService(this, intent);
    }


    @Override
    protected void initUIandEvent() {
        setContentView(R.layout.activity_screen_share);
        agEventHandler = new MyEngineEventHandler() {
            @Override
            public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
                shareScreen();
                super.onJoinChannelSuccess(channel, uid, elapsed);
            }
        };
        mpm = (MediaProjectionManager) getSystemService
                (Context.MEDIA_PROJECTION_SERVICE);
        rtcEngine().addHandler(agEventHandler);
    }

    @Override
    protected void deInitUIandEvent() {

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        rtcEngine().removeHandler(agEventHandler);
    }

    private void joinChannel(String channel) {
        joinChannel(channel, 1);
    }

    @Override
    public void finish() {
        rtcEngine().leaveChannel();
        unbindVideoService();
        super.finish();
    }


    private void unbindVideoService() {
        if (mServiceConnection != null) {
            mService.getService().stopSelf();
            unbindService(mServiceConnection);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PROJECTION_REQ_CODE) {
            Log.e(TAG, "Unknown request code: " + requestCode);
            return;
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(this,
                    "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
            return;
        }
        bindVideoService(data);
    }

    private void shareScreen() {
        if (!start && mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), PROJECTION_REQ_CODE);
        }
    }


    public void toggle(View view) {
        if (start) {
            leaveChannel(getIntent().getStringExtra(ConstantApp.ACTION_KEY_CHANNEL_NAME));
            finish();
        } else {
            rtcEngine().setVideoEncoderConfiguration(new VideoEncoderConfiguration(
                    new VideoEncoderConfiguration.VideoDimensions(DISPLAY_WIDTH, DISPLAY_HEIGHT),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_24,
                    VideoEncoderConfiguration.STANDARD_BITRATE, VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
            ));
            rtcEngine().setClientRole(Constants.CLIENT_ROLE_BROADCASTER);
            rtcEngine().setExternalVideoSource(true, true, true);
            joinChannel(getIntent().getStringExtra(ConstantApp.ACTION_KEY_CHANNEL_NAME));
        }
    }


    private class VideoInputServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mService = (ScreenShareService.ScreenShareBinder) iBinder;
            start = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            start = false;
            mService = null;
        }
    }


}