#version 450

// Cannoli CRT Lite — single-pass, bilinear sampling
// Curvature, scanlines, aperture mask, phosphor sweep
// No Lanczos, no glow, no noise, no gamma, no vignette

#pragma parameter curvature "Curvature" 1.7 0.0 2.0 0.1
#pragma parameter scanline_strength "Scanlines" 0.65 0.0 1.0 0.05
#pragma parameter mask_strength "Mask Strength" 0.2 0.0 1.0 0.01
#pragma parameter sweep "Sweep" 1.0 0.0 1.0 0.05
#pragma parameter sweep_bright "Sweep Brightness" 0.35 0.0 1.0 0.05
#pragma parameter sweep_period "Sweep Period (frames)" 600.0 60.0 3600.0 60.0
#pragma parameter bright_boost "Brightness" 1.2 1.0 2.0 0.01

layout(push_constant) uniform Push
{
    vec4 SourceSize;
    vec4 OriginalSize;
    vec4 OutputSize;
    uint FrameCount;
} params;

layout(std140, set = 0, binding = 0) uniform UBO
{
    mat4 MVP;
    float curvature;
    float scanline_strength;
    float mask_strength;
    float sweep;
    float sweep_bright;
    float sweep_period;
    float bright_boost;
} global;

#define curvature global.curvature
#define scanline_strength global.scanline_strength
#define mask_strength global.mask_strength
#define sweep global.sweep
#define sweep_bright global.sweep_bright
#define sweep_period global.sweep_period
#define bright_boost global.bright_boost

#pragma stage vertex
layout(location = 0) in vec4 Position;
layout(location = 1) in vec2 TexCoord;
layout(location = 0) out vec2 vTexCoord;

void main() {
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
}

#pragma stage fragment
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 FragColor;
layout(set = 0, binding = 2) uniform sampler2D Source;

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

    vec3 col = texture(Source, xy).rgb;

    // Scanlines — triangle wave, no transcendental functions
    float scan_y = fract(xy.y * params.SourceSize.y);
    float scan = abs(scan_y * 2.0 - 1.0); // triangle [0,1], peak at row center
    float scan_weight = 1.0 - (1.0 - scan) * scanline_strength;
    col *= scan_weight;

    // RGB aperture mask (vertical trinitron stripes)
    float m = 1.0 - mask_strength;
    float px = mod(floor(xy.x * params.OutputSize.x), 3.0);
    vec3 mask_w = vec3(
        mix(1.0, m, step(1.0, px)),
        mix(m, mix(1.0, m, step(2.0, px)), step(1.0, px)),
        mix(m, 1.0, step(2.0, px))
    );
    col *= mask_w;

    // Phosphor sweep
    // vTexCoord.y 0 is the top of the picture in both backends, so a rising
    // phase sweeps downward. Same expression in the .glsl; see the report.
    float sweepPhase = fract(float(params.FrameCount) / sweep_period);
    float band = smoothstep(0.04, 0.0, abs(xy.y - sweepPhase));
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
