package io.agora.openvcall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.view.Surface;

import java.io.IOException;

import io.agora.openvcall.screenshare.gles.ProgramTextureOES;
import io.agora.rtc.video.AgoraVideoFrame;
import io.agora.openvcall.screenshare.gles.GLThreadContext;
import io.agora.openvcall.screenshare.gles.core.EglCore;
import io.agora.openvcall.screenshare.gles.core.GlUtil;

public class ScreenShareService extends Service implements SurfaceTexture.OnFrameAvailableListener {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "ExternalVideo";
    private static final int DEFAULT_CAPTURE_WIDTH = 1280;
    private static final int DEFAULT_CAPTURE_HEIGHT = 720;
    private static final int LOCAL_VIDEO_WIDTH = 1280;
    private static final int LOCAL_VIDEO_HEIGHT = 720;
    private static final int DISPLAY_WIDTH = 720;
    private static final int DISPLAY_HEIGHT = 1280;
    AGApplication agoraApplication;
    StreamThread mThread;
    Surface surface;
    ScreenShareBinder screenShareBinder;
    MediaProjectionManager mpm;
    MediaProjectionCallback mediaProjectionCallback;
    SurfaceTexture surfaceTexture;
    GLThreadContext glThreadContext;
    private EGLSurface mEglSurface;
    private ProgramTextureOES mProgram;
    private EglCore mEglCore;
    private EGLSurface mDummySurface;
    private int mTextureId;
    //    private EGLSurface mDrawSurface;
    private float[] mTransform = new float[16];
    private float[] mMVPMatrix = new float[16];
    private boolean mMVPMatrixInit = false;
    private boolean mTextureDestroyed;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay1, virtualDisplay2;
    private MediaProjection mediaProjection;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaRecorder = new MediaRecorder();
        agoraApplication = (AGApplication) getApplication();
        screenShareBinder = new ScreenShareBinder();
        mediaProjectionCallback = new MediaProjectionCallback();
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
        /*mThread = new StreamThread();
        mThread.start();*/
    }

    @Override
    public boolean onUnbind(Intent intent) {
//        mThread.setThreadStopped();
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
        mpm = (MediaProjectionManager) getSystemService
                (Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(-1, intent);
        mediaProjection.registerCallback(mediaProjectionCallback, null);
        initRecorder();
        prepareRecorder();
        setMediaProjection(mediaProjection);
        mediaRecorder.start();
        startSourceManager();
        return START_STICKY;
    }

    private void stopScreenSharing() {
        if (virtualDisplay1 == null) {
            return;
        }
        virtualDisplay1.release();
        virtualDisplay2.release();
        if (mediaRecorder != null) {
//            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
        }
        surfaceTexture.release();
        surface.release();
        mProgram.release();
        mEglCore.makeNothingCurrent();
        mEglCore.releaseSurface(mEglSurface);
        GlUtil.deleteTextureObject(mTextureId);
        mTextureId = 0;
        mEglCore.release();
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
            mTextureDestroyed = false;
            mEglCore = new EglCore();
            mEglSurface = mEglCore.createOffscreenSurface(1, 1);
            mEglCore.makeCurrent(mEglSurface);
            mTextureId = GlUtil.createTextureObject(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            surfaceTexture = new SurfaceTexture(mTextureId);
            surface = new Surface(surfaceTexture);
            glThreadContext = new GLThreadContext();
            glThreadContext.eglCore = mEglCore;
            glThreadContext.context = mEglCore.getEGLContext();
            glThreadContext.program = new ProgramTextureOES();
            mDummySurface = mEglCore.createOffscreenSurface(1, 1);
            mEglCore.makeCurrent(mDummySurface);
            surfaceTexture.setOnFrameAvailableListener(this);
            virtualDisplay1 = mediaProjection.createVirtualDisplay("v1",
                    DISPLAY_WIDTH, DISPLAY_HEIGHT, 3,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);
            virtualDisplay2 = mediaProjection.createVirtualDisplay("v2",
                    DISPLAY_WIDTH, DISPLAY_HEIGHT, 3,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);
            mSurfaceWidth = virtualDisplay1.getDisplay().getWidth();
            mSurfaceHeight = virtualDisplay1.getDisplay().getHeight();
            surfaceTexture.setDefaultBufferSize(mSurfaceWidth, mSurfaceHeight);
            mProgram = new ProgramTextureOES();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * To notify when the preview surface texture has been updated.
     * Called by the system camera module.
     *
     * @param surfaceTexture camera preview surface texture.
     */
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mTextureDestroyed) return;

        try {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(mTransform);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // The rectangle ratio of frames and the screen surface
            // may be different, so cropping may happen when display
            // frames to the screen.
            calculateDisplayTransform();
            GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
//            mProgram.drawFrame(mTextureId, mTransform, mMVPMatrix);

            agoraApplication.rtcEngine().pushExternalVideoFrame(
                    buildVideoFrame(mTextureId, mTransform));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void calculateDisplayTransform() {
        // The display transformation matrix does not change
        // for the same camera when the screen orientation
        // remains the same.
        if (mMVPMatrixInit) return;

        // For simplicity, we only consider the activity as
        // portrait mode. In this case, the captured images
        // should be rotated 90 degrees (left or right).
        // Thus the frame width and height should be swapped.
        float frameRatio = DEFAULT_CAPTURE_HEIGHT / (float) DEFAULT_CAPTURE_WIDTH;
        float surfaceRatio = mSurfaceWidth / (float) mSurfaceHeight;
        Matrix.setIdentityM(mMVPMatrix, 0);

        if (frameRatio >= surfaceRatio) {
            float w = DEFAULT_CAPTURE_WIDTH * surfaceRatio;
            float scaleW = DEFAULT_CAPTURE_HEIGHT / w;
            Matrix.scaleM(mMVPMatrix, 0, scaleW, 1, 1);
        } else {
            float h = DEFAULT_CAPTURE_HEIGHT / surfaceRatio;
            float scaleH = DEFAULT_CAPTURE_WIDTH / h;
            Matrix.scaleM(mMVPMatrix, 0, 1, scaleH, 1);
        }
        mMVPMatrixInit = true;
    }

    private AgoraVideoFrame buildVideoFrame(int textureId, float[] transform) {
        AgoraVideoFrame frame = new AgoraVideoFrame();
        frame.textureID = textureId;
        frame.format = AgoraVideoFrame.FORMAT_TEXTURE_OES;
        frame.transform = transform;
        frame.stride = DEFAULT_CAPTURE_WIDTH;
        frame.height = DEFAULT_CAPTURE_HEIGHT;
        frame.eglContext14 = mEglCore.getEGLContext();
        frame.timeStamp = System.currentTimeMillis();
        return frame;
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
        public ScreenShareService getService() {
            return ScreenShareService.this;
        }


    }

    private class StreamThread extends Thread {
        private final String TAG = "stream_thread";
        private final int DEFAULT_WAIT_TIME = 1;
        int mVideoWidth;
        int mVideoHeight;
        private EglCore mEglCore;
        private EGLSurface mEglSurface;
        private int mTextureId;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private float[] mTransform = new float[16];
        private GLThreadContext mThreadContext;
        private volatile boolean mStopped;
        private volatile boolean mPaused;

        private void prepare() {
            mEglCore = new EglCore();
            mEglSurface = mEglCore.createOffscreenSurface(1, 1);
            mEglCore.makeCurrent(mEglSurface);
            mTextureId = GlUtil.createTextureObject(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurface = new Surface(mSurfaceTexture);
            mThreadContext = new GLThreadContext();
            mThreadContext.eglCore = mEglCore;
            mThreadContext.context = mEglCore.getEGLContext();
            mThreadContext.program = new ProgramTextureOES();
        }

        private void release() {
            mSurface.release();
            mEglCore.makeNothingCurrent();
            mEglCore.releaseSurface(mEglSurface);
            mSurfaceTexture.release();
            GlUtil.deleteTextureObject(mTextureId);
            mTextureId = 0;
            mEglCore.release();
        }

        @Override
        public void run() {
            prepare();

        /*    while (!mStopped) {
                if (mCurVideoInput != mNewVideoInput) {
                    Log.i(TAG, "New video input selected");
                    // Current video input is running, but we now
                    // introducing a new video type.
                    // The new video input type may be null, referring
                    // that we are not using any video.
                    if (mCurVideoInput != null) {
                        mCurVideoInput.onVideoStopped(mThreadContext);
                        Log.i(TAG, "recycle stopped input");
                    }

                    mCurVideoInput = mNewVideoInput;
                    if (mCurVideoInput != null) {
                        mCurVideoInput.onVideoInitialized(mSurface);
                        Log.i(TAG, "initialize new input");
                    }

                    if (mCurVideoInput == null) {
                        continue;
                    }

                    Size size = mCurVideoInput.onGetFrameSize();
                    mVideoWidth = size.getWidth();
                    mVideoHeight = size.getHeight();
                    mSurfaceTexture.setDefaultBufferSize(mVideoWidth, mVideoHeight);

                    if (mPaused) {
                        // If current thread is in pause state, it must be paused
                        // because of switching external video sources.
                        mPaused = false;
                    }
                } else if (mCurVideoInput != null && !mCurVideoInput.isRunning()) {
                    // Current video source has been stopped by other
                    // mechanisms (video playing has completed, etc).
                    // A callback method is invoked to do some collect
                    // or release work.
                    // Note that we also set the new video source null,
                    // meaning at meantime, we are not introducing new
                    // video types.
                    Log.i(TAG, "current video input is not running");
                    mCurVideoInput.onVideoStopped(mThreadContext);
                    mCurVideoInput = null;
                    mNewVideoInput = null;
                }

                if (mPaused || mCurVideoInput == null) {
                    waitForTime(DEFAULT_WAIT_TIME);
                    continue;
                }

                try {
                    mSurfaceTexture.updateTexImage();
                    mSurfaceTexture.getTransformMatrix(mTransform);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (mCurVideoInput != null) {
                    mCurVideoInput.onFrameAvailable(mThreadContext, mTextureId, mTransform);
                }

                mEglCore.makeCurrent(mEglSurface);
                GLES20.glViewport(0, 0, mVideoWidth, mVideoHeight);


                if (mConsumer != null) {
                    if (!mEglCore.isCurrent(mDrawSurface)) {
                        mEglCore.makeCurrent(mDrawSurface);
                    }

                    try {
                        mPreviewSurfaceTexture.updateTexImage();
                        mPreviewSurfaceTexture.getTransformMatrix(mTransform);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    // The rectangle ratio of frames and the screen surface
                    // may be different, so cropping may happen when display
                    // frames to the screen.
                    calculateDisplayTransform();
                    GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
                    mProgram.drawFrame(mPreviewTexture, mTransform, mMVPMatrix);
                    mEglCore.swapBuffers(mDrawSurface);

                    if (mChannelJoined) {
                        rtcEngine().pushExternalVideoFrame(
                                buildVideoFrame(mPreviewTexture, mTransform));
                    }
                    mConsumer.consumeTextureFrame(mTextureId,
                            MediaIO.PixelFormat.TEXTURE_OES.intValue(),
                            mVideoWidth, mVideoHeight, 0,
                            System.currentTimeMillis(), mTransform);
                }

                // The pace at which the output Surface is sampled
                // for video frames is controlled by the waiting
                // time returned from the external video source.
                waitForNextFrame();
            }

            if (mCurVideoInput != null) {
                // The manager will cause the current
                // video source to be stopped.
                mCurVideoInput.onVideoStopped(mThreadContext);
            }
            release();*/
        }

        void pauseThread() {
            mPaused = true;
        }

        void setThreadStopped() {
            mStopped = true;
        }

        private void waitForNextFrame() {
            waitForTime(1);
        }

        private void waitForTime(int time) {
            try {
                Thread.sleep(time);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }
}
