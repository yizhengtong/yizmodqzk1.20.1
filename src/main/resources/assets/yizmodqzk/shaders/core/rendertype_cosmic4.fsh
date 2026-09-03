#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float iTime;

in vec4 vertexColor;
in vec2 texCoord0;
in vec3 fPos;

out vec4 fragColor;

vec3 palette(float t) {
    float ct = cos(6.28318 * t);
    return vec3(0.5) + vec3(0.5) * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
}

float Hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.821));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec4 mask = texture(Sampler0, texCoord0.xy);
    if (mask.a < 0.05) { discard; }

    vec2 uv = fPos.xy * 0.8;
    float t = iTime * 0.5;
    vec3 col = vec3(0.05, 0.02, 0.1);

    for (int layer = 0; layer < 7; layer++) {
        float lf = float(layer) / 7.0;
        float depth = fract(lf + t * 0.02);
        float scale = mix(12.0, 1.0, depth);
        vec2 luv = uv * scale + float(layer) * 432.1;

        vec2 gv = fract(luv) - 0.5;
        vec2 id = floor(luv);

        for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x), float(y));
            float n = Hash21(id + offset);
            float starSize = fract(n * 149.1) * 0.5 + 0.3;
            vec2 starPos = vec2(n, fract(n * 34.0)) - 0.5;
            float d = length(gv - offset - starPos * 0.6);
            if (d < starSize * 0.15) {
                float bright = (1.0 - d / (starSize * 0.15))
                    * (sin(t * 3.0 + n * 100.0) * 0.4 + 0.6)
                    * depth * (1.0 - lf * 0.5);
                vec3 starCol = palette(n + t * 0.05) * 1.5;
                col += starCol * bright * 0.8;
                // small glow
                if (d < starSize * 0.04) {
                    col += starCol * bright * 1.5;
                }
            }
        }
    }

    col = clamp(col, 0.0, 1.0);
    fragColor = vec4(col, 0.5) * ColorModulator;
}
