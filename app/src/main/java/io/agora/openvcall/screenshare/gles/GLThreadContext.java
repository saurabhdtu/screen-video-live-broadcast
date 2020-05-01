package io.agora.openvcall.screenshare.gles;

import android.opengl.EGLContext;

import io.agora.openvcall.screenshare.gles.core.EglCore;
public class GLThreadContext {
    public EglCore eglCore;
    public EGLContext context;
    public ProgramTextureOES program;
}
