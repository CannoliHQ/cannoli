// Kawase blur kernel — 4-tap diamond, distance 0.5

#define KAWASE_DISTANCE 0.5

#if defined(VERTEX)

#if __VERSION__ >= 130
#define COMPAT_VARYING out
#define COMPAT_ATTRIBUTE in
#else
#define COMPAT_VARYING varying
#define COMPAT_ATTRIBUTE attribute
#endif

#ifdef GL_ES
#define COMPAT_PRECISION mediump
#else
#define COMPAT_PRECISION
#endif

COMPAT_ATTRIBUTE vec4 VertexCoord;
COMPAT_ATTRIBUTE vec4 TexCoord;
COMPAT_VARYING vec2 vTexCoord;

uniform mat4 MVPMatrix;

void main() {
    gl_Position = MVPMatrix * VertexCoord;
    vTexCoord = TexCoord.xy;
}

#elif defined(FRAGMENT)

#if __VERSION__ >= 130
#define COMPAT_VARYING in
#define COMPAT_TEXTURE texture
out vec4 FragColor;
#else
#define COMPAT_VARYING varying
#define COMPAT_TEXTURE texture2D
#define FragColor gl_FragColor
#endif

#ifdef GL_ES
precision mediump float;
#define COMPAT_PRECISION mediump
#else
#define COMPAT_PRECISION
#endif

uniform COMPAT_PRECISION vec2 TextureSize;
uniform sampler2D Source;
COMPAT_VARYING vec2 vTexCoord;

void main() {
    vec2 texelSize = 1.0 / TextureSize;
    vec2 step1 = KAWASE_DISTANCE * texelSize;
    vec4 color = COMPAT_TEXTURE(Source, vTexCoord + step1) * 0.25;
    color += COMPAT_TEXTURE(Source, vTexCoord - step1) * 0.25;
    vec2 step2 = vec2(step1.x, -step1.y);
    color += COMPAT_TEXTURE(Source, vTexCoord + step2) * 0.25;
    color += COMPAT_TEXTURE(Source, vTexCoord - step2) * 0.25;
    FragColor = color;
}

#endif
