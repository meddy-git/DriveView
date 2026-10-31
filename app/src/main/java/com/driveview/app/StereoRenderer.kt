package com.driveview.app

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Draws the camera frame twice (two rounded square tiles) with lens pre-distortion. */
class StereoRenderer : GLSurfaceView.Renderer {

    @Volatile var cfg: Config = Config(0.34f, 0.15f, 1.2f, 0.46f, 0.92f, 0.22f, 0.5f, true, true)

    private val pending = AtomicReference<Bitmap?>(null)

    private var program = 0
    private var tex = 0
    private var width = 1
    private var height = 1
    private var haveTex = false
    private var fw = 320f
    private var fh = 240f

    private var aPos = 0
    private var uTex = 0; private var uRes = 0; private var uL = 0; private var uR = 0
    private var uFrame = 0; private var uDist = 0; private var uRad = 0
    private var uFill = 0; private var uFlip = 0; private var uSharp = 0

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    /** Called from the network thread. Keeps only the newest frame. */
    fun submit(b: Bitmap) {
        pending.getAndSet(b)?.recycle()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        aPos = GLES20.glGetAttribLocation(program, "a")
        uTex = GLES20.glGetUniformLocation(program, "u_tex")
        uRes = GLES20.glGetUniformLocation(program, "u_res")
        uL = GLES20.glGetUniformLocation(program, "u_L")
        uR = GLES20.glGetUniformLocation(program, "u_R")
        uFrame = GLES20.glGetUniformLocation(program, "u_frame")
        uDist = GLES20.glGetUniformLocation(program, "u_dist")
        uRad = GLES20.glGetUniformLocation(program, "u_radius")
        uFill = GLES20.glGetUniformLocation(program, "u_fill")
        uFlip = GLES20.glGetUniformLocation(program, "u_flip")
        uSharp = GLES20.glGetUniformLocation(program, "u_sharp")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        haveTex = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val b = pending.getAndSet(null)
        if (b != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            fw = b.width.toFloat()
            fh = b.height.toFloat()
            b.recycle()
            haveTex = true
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!haveTex) return

        val c = cfg
        val w = width.toFloat()
        val h = height.toFloat()
        val g = Geo.tiles(w, h, c)   // [leftX, rightX, y, size]

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uRes, w, h)
        GLES20.glUniform4f(uL, g[0], g[2], g[3], g[3])
        GLES20.glUniform4f(uR, g[1], g[2], g[3], g[3])
        GLES20.glUniform2f(uFrame, fw, fh)
        GLES20.glUniform3f(uDist, c.k1, c.k2, c.zoom)
        GLES20.glUniform1f(uRad, g[3] * c.radius)
        GLES20.glUniform1f(uFill, 1f)
        GLES20.glUniform2f(uFlip, if (c.flipX) -1f else 1f, if (c.flipY) -1f else 1f)
        GLES20.glUniform1f(uSharp, c.sharp)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("DriveView", "Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERTEX = "attribute vec2 a; void main(){ gl_Position = vec4(a, 0.0, 1.0); }"

        private const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D u_tex;
            uniform vec2  u_res;
            uniform vec4  u_L;
            uniform vec4  u_R;
            uniform vec2  u_frame;
            uniform vec3  u_dist;
            uniform float u_radius;
            uniform float u_fill;
            uniform vec2  u_flip;
            uniform float u_sharp;

            void main(){
              vec2 px = vec2(gl_FragCoord.x, u_res.y - gl_FragCoord.y);
              vec4 w  = (px.x < u_res.x * 0.5) ? u_L : u_R;
              vec2 hs = w.zw * 0.5;
              vec2 p  = px - (w.xy + hs);

              vec2 q = abs(p) - (hs - vec2(u_radius));
              float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - u_radius;
              if (d > 0.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

              float r2 = dot(p, p) / dot(hs, hs);
              float f  = 1.0 + u_dist.x * r2 + u_dist.y * r2 * r2;
              vec2  s  = p * f * u_flip / u_dist.z;

              vec2  k  = w.zw / u_frame;
              float sc = (u_fill > 0.5) ? max(k.x, k.y) : min(k.x, k.y);
              vec2  uv = 0.5 + s / (sc * u_frame);
              if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
              }

              vec3 c = texture2D(u_tex, uv).rgb;
              if (u_sharp > 0.0) {
                vec2 t = 1.0 / u_frame;
                vec3 b = (texture2D(u_tex, uv + vec2(t.x, 0.0)).rgb + texture2D(u_tex, uv - vec2(t.x, 0.0)).rgb
                        + texture2D(u_tex, uv + vec2(0.0, t.y)).rgb + texture2D(u_tex, uv - vec2(0.0, t.y)).rgb) * 0.25;
                c = clamp(c + u_sharp * (c - b), 0.0, 1.0);
              }
              gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
