package io.agora.openvcall.screenshare;

import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.projection.MediaProjection;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import io.agora.openvcall.AGApplication;
import io.agora.openvcall.screenshare.gles.GLThreadContext;
import io.agora.openvcall.screenshare.gles.ProgramTextureOES;
import io.agora.openvcall.screenshare.gles.core.EglCore;
import io.agora.openvcall.screenshare.gles.core.GlUtil;
import io.agora.rtc.mediaio.IVideoFrameConsumer;
import io.agora.rtc.mediaio.IVideoSource;
import io.agora.rtc.mediaio.MediaIO;

public class ScreenInputManager implements IVideoSource {
    public static final int TYPE_LOCAL_VIDEO = 1;
    public static final int TYPE_SCREEN_SHARE = 2;
    public static final int TYPE_AR_CORE = 3;
    public static final String FLAG_VIDEO_PATH = "flag-local-video";
    public static final String FLAG_SCREEN_WIDTH = "screen-width";
    public static final String FLAG_SCREEN_HEIGHT = "screen-height";
    public static final String FLAG_SCREEN_DPI = "screen-dpi";
    public static final String FLAG_FRAME_RATE = "screen-frame-rate";
    private static final int DISPLAY_WIDTH = 720;
    private static final int DISPLAY_HEIGHT = 1280;
    private static final String TAG = ScreenInputManager.class.getSimpleName();
    private static final int DEFAULT_SCREEN_WIDTH = 640;
    private static final int DEFAULT_SCREEN_HEIGHT = 480;
    private static final int DEFAULT_SCREEN_DPI = 3;
    private static final int DEFAULT_FRAME_RATE = 15;
    private static final int DEFAULT_CAPTURE_WIDTH = 1280;
    private static final int DEFAULT_CAPTURE_HEIGHT = 720;
    private static final int LOCAL_VIDEO_WIDTH = 1280;
    private static final int LOCAL_VIDEO_HEIGHT = 720;
    private AGApplication mApplication;
    private ExternalVideoInputThread mThread;
    private volatile IExternalVideoInput mCurVideoInput;
    private volatile IExternalVideoInput mNewVideoInput;

    // RTC video interface to send video
    private volatile IVideoFrameConsumer mConsumer;
    private volatile MediaProjection mediaProjection;

    public ScreenInputManager(AGApplication application) {
        mApplication = application;
    }

    void start() {
        mThread = new ExternalVideoInputThread();
        mThread.start();
    }

    boolean setExternalVideoInput(MediaProjection mediaProjection, Intent intent) {
        this.mediaProjection = mediaProjection;
        // Do not reset current input if the target type is
        // the same as the current which is still running.
        if (mCurVideoInput != null
                && mCurVideoInput.isRunning()) {
            return false;
        }

        IExternalVideoInput input;
        int width = intent.getIntExtra(FLAG_SCREEN_WIDTH, DEFAULT_SCREEN_WIDTH);
        int height = intent.getIntExtra(FLAG_SCREEN_HEIGHT, DEFAULT_SCREEN_HEIGHT);
        int dpi = intent.getIntExtra(FLAG_SCREEN_DPI, DEFAULT_SCREEN_DPI);
        int fps = intent.getIntExtra(FLAG_FRAME_RATE, DEFAULT_FRAME_RATE);
        Log.i(TAG, "ScreenShare:" + width + "|" + height + "|" + dpi + "|" + fps);
        input  = new ScreenShareInput(mApplication.getApplicationContext(),
                width, height, dpi, fps, intent, mediaProjection);
        setExternalVideoInput(input);
        return true;
    }

    private void setExternalVideoInput(IExternalVideoInput source) {

        if (mThread != null && mThread.isAlive()) mThread.pauseThread();
        mNewVideoInput = source;
    }

    void stop() {
        mThread.setThreadStopped();
    }

    @Override
    public boolean onInitialize(IVideoFrameConsumer consumer) {
        mConsumer = consumer;
        return true;
    }

    @Override
    public boolean onStart() {
        return true;
    }

    @Override
    public void onStop() {

    }

    @Override
    public void onDispose() {
        mConsumer = null;
    }

    @Override
    public int getBufferType() {
        return MediaIO.BufferType.TEXTURE.intValue();
    }

    private class ExternalVideoInputThread extends Thread {
        private final String TAG = ExternalVideoInputThread.class.getSimpleName();
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
            mApplication.rtcEngine().setVideoSource(ScreenInputManager.this);
        }

        private void release() {
            mApplication.rtcEngine().setVideoSource(null);
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

            while (!mStopped) {
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
            release();
        }

        void pauseThread() {
            mPaused = true;
        }

        void setThreadStopped() {
            mStopped = true;
        }

        private void waitForNextFrame() {
            int wait = mCurVideoInput != null
                    ? mCurVideoInput.timeToWait()
                    : DEFAULT_WAIT_TIME;
            waitForTime(wait);
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
