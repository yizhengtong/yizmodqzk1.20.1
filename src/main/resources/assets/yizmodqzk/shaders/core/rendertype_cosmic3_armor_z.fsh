#version 150
#define M_PI 3.1415926535897932384626433832795

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float iTime;
uniform float cosmicuvs[40];

in vec4 vertexColor;
in vec2 texCoord0;
in vec3 fPos;

out vec4 fragColor;

mat4 rotationMatrix(vec3 axis, float angle) {
    axis = normalize(axis);
    float s = sin(angle), c = cos(angle), oc = 1.0 - c;
    return mat4(oc*axis.x*axis.x+c, oc*axis.x*axis.y-axis.z*s, oc*axis.z*axis.x+axis.y*s, 0,
                oc*axis.x*axis.y+axis.z*s, oc*axis.y*axis.y+c, oc*axis.y*axis.z-axis.x*s, 0,
                oc*axis.z*axis.x-axis.y*s, oc*axis.y*axis.z+axis.x*s, oc*axis.z*axis.z+c, 0,
                0,0,0,1);
}

void main() {
    vec4 mask = texture(Sampler0, texCoord0);
    // discard removed — @Redirect mode needs every fragment to pass
    float time = iTime * 0.4;
    vec4 col = vec4(0.08, 0.0, 0.04, 1.0);
    float pulse = mod(time * 0.0025, 1.0);
    col.g = sin(pulse * M_PI * 2.0) * 0.06 + 0.18;
    col.b = cos(pulse * M_PI * 2.0) * 0.04 + 0.25;

    vec4 dir = normalize(vec4(-fPos, 0));

    for (int i = 0; i < 16; i++) {
        int mult = 16 - i;
        int j = i + 7;
        float rand1 = float(j * j * 4321 + j * 8) * 2.0;
        int k = j + 1;
        float rand2 = float(k * k * k * 239 + k * 37) * 3.6;
        float rand3 = rand1 * 347.4 + rand2 * 63.4;
        vec3 axis = normalize(vec3(sin(rand1), sin(rand2), cos(rand3)));
        vec4 ray = dir * rotationMatrix(axis, mod(rand3, 2.0 * M_PI));

        float rawu = 0.5 + atan(ray.z, ray.x) / (2.0 * M_PI);
        float rawv = 0.5 + asin(clamp(ray.y, -1.0, 1.0)) / M_PI;
        float scale = float(mult) * 0.5 + 2.75;
        float u = rawu * scale;
        float v = (rawv + time * 0.0003) * scale * 0.6;

        int uvtiles = 16;
        int tu = int(mod(floor(u * float(uvtiles)), float(uvtiles)));
        int tv = int(mod(floor(v * float(uvtiles)), float(uvtiles)));
        int position = (171 * tu + 489 * tv + 303 * (i + 31) + 17209) ^ 10;
        int symbol = int(mod(float(position), 101.0));
        int rotation = int(mod(float(tu * tu * tv + tu + 3 + tv * i), 8.0));
        bool flip = false;
        if (rotation >= 4) { rotation -= 4; flip = true; }

        if (symbol >= 0 && symbol < 10) {
            float ru = clamp(mod(u * float(uvtiles) - float(tu), 1.0), 0.0, 1.0);
            float rv = clamp(mod(v * float(uvtiles) - float(tv), 1.0), 0.0, 1.0);
            if (flip) ru = 1.0 - ru;
            float oru = ru, orv = rv;
            if (rotation == 1) { oru = 1.0 - rv; orv = ru; }
            else if (rotation == 2) { oru = 1.0 - ru; orv = 1.0 - rv; }
            else if (rotation == 3) { oru = rv; orv = 1.0 - ru; }

            int b = symbol * 4;
            float umin = cosmicuvs[b], vmin = cosmicuvs[b + 1];
            float umax = cosmicuvs[b + 2], vmax = cosmicuvs[b + 3];
            vec2 cosmictex = vec2(umin + (umax - umin) * oru, vmin + (vmax - vmin) * orv);
            vec4 tcol = texture(Sampler0, cosmictex);

            float a = tcol.r * (0.5 + 1.0 / float(mult))
                    * (1.0 - smoothstep(0.15, 0.48, abs(rawv - 0.5)));
            float r = mod(rand1, 29.0) / 29.0 * 0.3 + 0.4;
            float g = mod(rand2, 35.0) / 35.0 * 0.4 + 0.6;
            float bb = mod(rand1, 17.0) / 17.0 * 0.3 + 0.7;
            col = col + vec4(r, g, bb, 1.0) * a;
        }
    }

    vec3 shade = vertexColor.rgb * 0.2 + vec3(0.8);
    col.rgb *= shade;
    col = clamp(col, 0.0, 1.0);
    col.a *= 0.5;  // fixed alpha — @Redirect mode, skip block-atlas alpha
    fragColor = col * ColorModulator;
}
