// Cannoli CRT Lite — single-pass, bilinear sampling
// Curvature, scanlines, aperture mask, phosphor sweep
// No Lanczos, no glow, no noise, no gamma, no vignette

#pragma parameter curvature "Curvature" 1.7 0.0 2.0 0.1
#pragma parameter scanline_strength "Scanlines" 0.65 0.0 1.0 0.05
#pragma parameter mask_strength "Mask Strength" 0.2 0.0 1.0 0.01
#pragma parameter sweep "Sweep" 1.0 0.0 1.0 0.05
#pragma parameter sweep_bright "Sweep Brightness" 0.35 0.0 1.0 0.05
#pragma parameter bright_boost "Brightness" 1.2 1.0 2.0 0.01

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
precision highp float;
#define COMPAT_PRECISION highp
#else
#define COMPAT_PRECISION
#endif

uniform COMPAT_PRECISION vec2 OutputSize;
uniform COMPAT_PRECISION vec2 TextureSize;
uniform COMPAT_PRECISION float SweepPhase;

uniform sampler2D Texture;

#ifdef PARAMETER_UNIFORM
uniform COMPAT_PRECISION float curvature;
uniform COMPAT_PRECISION float scanline_strength;
uniform COMPAT_PRECISION float mask_strength;
uniform COMPAT_PRECISION float sweep;
uniform COMPAT_PRECISION float sweep_bright;
uniform COMPAT_PRECISION float bright_boost;
#else
#define curvature 1.7
#define scanline_strength 0.65
#define mask_strength 0.2
#define sweep 1.0
#define sweep_bright 0.35
#define bright_boost 1.2
#endif

COMPAT_VARYING vec2 vTexCoord;

vec2 Warp(vec2 pos) {
    pos = pos * 2.0 - 1.0;
    pos *= vec2(1.0 + pos.y * pos.y * 0.0276,
                1.0 + pos.x * pos.x * 0.0414);
    return pos * 0.5 + 0.5;
}

void main() {
    vec2 xy = mix(vTexCoord, Warp(vTexCoord), curvature);

    if (xy.x < 0.0 || xy.x > 1.0 || xy.y < 0.0 || xy.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec3 col = COMPAT_TEXTURE(Texture, xy).rgb;

    // Scanlines — triangle wave, no transcendental functions
    float scan_y = fract(xy.y * TextureSize.y);
    float scan = abs(scan_y * 2.0 - 1.0); // triangle [0,1], peak at row center
    float scan_weight = 1.0 - (1.0 - scan) * scanline_strength;
    col *= scan_weight;

    // RGB aperture mask (vertical trinitron stripes)
    float m = 1.0 - mask_strength;
    float px = mod(floor(xy.x * OutputSize.x), 3.0);
    vec3 mask_w = vec3(
        mix(1.0, m, step(1.0, px)),
        mix(m, mix(1.0, m, step(2.0, px)), step(1.0, px)),
        mix(m, 1.0, step(2.0, px))
    );
    col *= mask_w;

    // Phosphor sweep
    float band = smoothstep(0.04, 0.0, abs(xy.y - (1.0 - SweepPhase)));
    col *= 1.0 + band * sweep_bright * sweep;

    // Corner rounding
    if (curvature > 0.01) {
        vec2 corn = min(xy, 1.0 - xy);
        corn.x = 0.0001 / corn.x;
        if (corn.y <= corn.x || corn.x < 0.0001)
            col = vec3(0.0);
    }

    FragColor = vec4(col * bright_boost, 1.0);
}

#endif
