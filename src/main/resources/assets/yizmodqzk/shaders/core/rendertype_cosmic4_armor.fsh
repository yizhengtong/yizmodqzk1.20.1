#version 150
uniform sampler2D Sampler0;
// 'Warp Speed' by David Hoskins 2013 — armor variant


uniform vec4 ColorModulator;
uniform float iTime;

in vec4 vertexColor;
in vec2 texCoord0;
in vec3 fPos;

out vec4 fragColor;

void main() {
    vec4 mask = texture(Sampler0, texCoord0);
    if (mask.a < 0.05) { discard; }
    
    // discard removed — @Redirect mode needs every fragment to pass
    float time = (iTime + 29.0) * 60.0;
    float s = 0.0, v = 0.0;
    vec2 uv = fPos.xy * 4.0;
    float t = time * 0.005;
    uv.x += sin(t) * 0.3;
    float si = sin(t * 1.5);
    float co = cos(t);
    uv *= mat2(co, si, -si, co);

    vec3 col = vec3(0.0);
    vec3 init = vec3(0.25, 0.25 + sin(time * 0.001) * 0.1, time * 0.0008);

    for (int r = 0; r < 100; r++) {
        vec3 p = init + s * vec3(uv, 0.143);
        p.z = mod(p.z, 2.0);
        for (int i = 0; i < 10; i++) p = abs(p * 2.04) / dot(p, p) - 0.75;
        v += length(p * p) * smoothstep(0.0, 0.5, 0.9 - s) * 0.002;
        col += vec3(v * 0.8, 1.1 - s * 0.5, 0.7 + v * 0.5) * v * 0.013;
        s += 0.01;
    }

    vec3 shade = vertexColor.rgb * 0.2 + vec3(0.8);
    col.rgb *= shade;
    col = clamp(col, 0.0, 1.0);
    fragColor = vec4(col, mask.a) * ColorModulator;
}
