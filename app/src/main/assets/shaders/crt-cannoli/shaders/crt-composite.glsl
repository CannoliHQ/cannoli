// Cannoli CRT — hybrid shader
// CRT base: EasyMode by EasyMode (GPL)
// Curvature, sweep, vignette, glow, noise: Cannoli

#pragma parameter curvature "Curvature" 1.7 0.0 2.0 0.1
#pragma parameter scanline_strength "Scanlines" 0.75 0.0 1.0 0.05
#pragma parameter mask_strength "Mask Strength" 0.3 0.0 1.0 0.01
#pragma parameter mask_size "Mask Size" 1.0 1.0 100.0 1.0
#pragma parameter mask_dot_width "Mask Dot Width" 1.0 1.0 100.0 1.0
#pragma parameter mask_dot_height "Mask Dot Height" 1.0 1.0 100.0 1.0
#pragma parameter mask_stagger "Mask Stagger" 0.0 0.0 100.0 1.0
#pragma parameter sharpness_h "Sharpness Horizontal" 0.5 0.0 1.0 0.05
#pragma parameter sharpness_v "Sharpness Vertical" 1.0 0.0 1.0 0.05
#pragma parameter gamma_input "Gamma Input" 2.0 0.1 5.0 0.1
#pragma parameter gamma_output "Gamma Output" 1.8 0.1 5.0 0.1
#pragma parameter bright_boost "Brightness" 1.2 1.0 2.0 0.01
#pragma parameter vignette "Vignette" 0.5 0.0 2.0 0.05
#pragma parameter glow_strength "Glow" 0.25 0.0 1.0 0.05
#pragma parameter sweep "Sweep" 1.0 0.0 1.0 0.05
#pragma parameter sweep_bright "Sweep Brightness" 0.35 0.0 1.0 0.05
#pragma parameter noise_amount "Noise" 0.15 0.0 1.0 0.05

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
uniform COMPAT_PRECISION vec2 OrigTextureSize;
uniform COMPAT_PRECISION int FrameCount;
uniform COMPAT_PRECISION float SweepPhase;

uniform sampler2D Source;
uniform sampler2D Original;

#ifdef PARAMETER_UNIFORM
uniform COMPAT_PRECISION float curvature;
uniform COMPAT_PRECISION float scanline_strength;
uniform COMPAT_PRECISION float mask_strength;
uniform COMPAT_PRECISION float mask_size;
uniform COMPAT_PRECISION float mask_dot_width;
uniform COMPAT_PRECISION float mask_dot_height;
uniform COMPAT_PRECISION float mask_stagger;
uniform COMPAT_PRECISION float sharpness_h;
uniform COMPAT_PRECISION float sharpness_v;
uniform COMPAT_PRECISION float gamma_input;
uniform COMPAT_PRECISION float gamma_output;
uniform COMPAT_PRECISION float bright_boost;
uniform COMPAT_PRECISION float vignette;
uniform COMPAT_PRECISION float glow_strength;
uniform COMPAT_PRECISION float sweep;
uniform COMPAT_PRECISION float sweep_bright;
uniform COMPAT_PRECISION float noise_amount;
#else
#define curvature 1.7
#define scanline_strength 0.75
#define mask_strength 0.3
#define mask_size 1.0
#define mask_dot_width 1.0
#define mask_dot_height 1.0
#define mask_stagger 0.0
#define sharpness_h 0.5
#define sharpness_v 1.0
#define gamma_input 2.0
#define gamma_output 1.8
#define bright_boost 1.2
#define vignette 0.5
#define glow_strength 0.25
#define sweep 1.0
#define sweep_bright 0.35
#define noise_amount 0.15
#endif

COMPAT_VARYING vec2 vTexCoord;

#define FIX(c) max(abs(c), 1e-5)
#define PI 3.141592653589
#define TEX2D(c) COMPAT_TEXTURE(Original, c)

vec2 Warp(vec2 pos) {
    pos = pos * 2.0 - 1.0;
    pos *= vec2(1.0 + pos.y * pos.y * 0.0276,
                1.0 + pos.x * pos.x * 0.0414);
    return pos * 0.5 + 0.5;
}

float curve_distance(float x, float sharp) {
    float x_step = step(0.5, x);
    float curve = 0.5 - sqrt(0.25 - (x - x_step) * (x - x_step)) * sign(0.5 - x);
    return mix(x, curve, sharp);
}

mat4 get_color_matrix(vec2 co, vec2 dx) {
    return mat4(TEX2D(co - dx), TEX2D(co), TEX2D(co + dx), TEX2D(co + 2.0 * dx));
}

vec3 filter_lanczos(vec4 coeffs, mat4 color_matrix) {
    vec4 col = color_matrix * coeffs;
    vec4 sample_min = min(color_matrix[1], color_matrix[2]);
    vec4 sample_max = max(color_matrix[1], color_matrix[2]);
    col = clamp(col, sample_min, sample_max);
    return col.rgb;
}

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 origSize = OrigTextureSize;
    vec2 origPx = 1.0 / origSize;
    vec2 xy = mix(vTexCoord, Warp(vTexCoord), curvature);

    if (xy.x < 0.0 || xy.x > 1.0 || xy.y < 0.0 || xy.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 dx = vec2(origPx.x, 0.0);
    vec2 dy = vec2(0.0, origPx.y);
    vec2 pix_co = xy * origSize - vec2(0.5);
    vec2 tex_co = (floor(pix_co) + vec2(0.5)) * origPx;
    vec2 dist = fract(pix_co);

    float curve_x = curve_distance(dist.x, sharpness_h * sharpness_h);
    vec4 coeffs = PI * vec4(1.0 + curve_x, curve_x, 1.0 - curve_x, 2.0 - curve_x);
    coeffs = FIX(coeffs);
    coeffs = 2.0 * sin(coeffs) * sin(coeffs * 0.5) / (coeffs * coeffs);
    coeffs /= dot(coeffs, vec4(1.0));

    vec3 col = filter_lanczos(coeffs, get_color_matrix(tex_co, dx));
    vec3 col2 = filter_lanczos(coeffs, get_color_matrix(tex_co + dy, dx));
    col = mix(col, col2, curve_distance(dist.y, sharpness_v));
    col = pow(col, vec3(gamma_input));

    float luma = dot(vec3(0.2126, 0.7152, 0.0722), col);
    float bright = (max(col.r, max(col.g, col.b)) + luma) * 0.5;
    float scan_beam = clamp(bright * 1.5, 1.0, 1.5);
    float scan_weight = 1.0 - pow(cos(xy.y * 2.0 * PI * origSize.y) * 0.5 + 0.5, scan_beam) * scanline_strength;

    float mask = 1.0 - mask_strength;
    vec2 mod_fac = floor(xy * OutputSize * origSize / (origSize * vec2(mask_size, mask_dot_height * mask_size)));
    int dot_no = int(mod((mod_fac.x + mod(mod_fac.y, 2.0) * mask_stagger) / mask_dot_width, 3.0));
    vec3 mask_weight;
    if      (dot_no == 0) mask_weight = vec3(1.0,  mask, mask);
    else if (dot_no == 1) mask_weight = vec3(mask, 1.0,  mask);
    else                  mask_weight = vec3(mask, mask, 1.0);

    col *= vec3(scan_weight);
    col = mix(col, col * vec3(1.0 / scan_weight), bright * 0.35);
    col *= mask_weight;
    col = pow(col, vec3(1.0 / gamma_output));

    float vigE = 0.08 + 0.12 * vignette;
    float vx = smoothstep(0.0, vigE, xy.x) * smoothstep(1.0, 1.0 - vigE, xy.x);
    float vy = smoothstep(0.0, vigE, xy.y) * smoothstep(1.0, 1.0 - vigE, xy.y);
    col *= mix(1.0, vx * vy, vignette);

    float sweepPhase = 1.0 - SweepPhase;
    float band = smoothstep(0.04, 0.0, abs(xy.y - sweepPhase));
    col *= 1.0 + band * sweep_bright * sweep;

    vec3 glow = COMPAT_TEXTURE(Source, xy).rgb;
    col = 1.0 - (1.0 - col) * (1.0 - glow * glow_strength);

    if (curvature > 0.01) {
        vec2 corn = min(xy, 1.0 - xy);
        corn.x = 0.0001 / corn.x;
        if (corn.y <= corn.x || corn.x < 0.0001)
            col = vec3(0.0);
    }

    float ntime = fract(float(FrameCount)) * 1000.0;
    vec2 seed = floor(vTexCoord * OutputSize) + vec2(ntime, ntime * 1.7);
    float n = (rand(seed) - 0.5) * 0.2 * noise_amount;
    col += vec3(n);

    FragColor = vec4(col * bright_boost, 1.0);
}

#endif
