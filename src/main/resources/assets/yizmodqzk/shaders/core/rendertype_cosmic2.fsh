#version 150

#define PI 3.1415926

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float iTime;

in vec4 vertexColor;
in vec2 texCoord0;
in vec3 fPos;

out vec4 fragColor;

mat2 ROT45_NoTrig = mat2(0.707, -0.707, 0.707, 0.707);

float Hash21(vec2 p) {
    p = fract(p * vec2(132.34, 456.76));
    p += dot(p, p + 61.477);
    return fract(p.x * p.y);
}

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - (k * h * (1.0 - h));
}

float smax(float a, float b, float k) {
    return smin(a, b, -k);
}

vec3 HSVtoRGB(vec3 hsv) {
    float hueSlice = 6.0 * hsv.x;
    float hueSliceInteger = floor(hueSlice);
    float hueSliceInterpolant = fract(hueSlice);
    vec3 tempRGB = vec3(hsv.z * (1.0 - hsv.y),
        hsv.z * (1.0 - hsv.y * hueSliceInterpolant),
        hsv.z * (1.0 - hsv.y * (1.0 - hueSliceInterpolant)));
    float isOddSlice = mod(hueSliceInteger, 2.0);
    float threeSliceSelector = 0.5 * (hueSliceInteger - isOddSlice);
    vec3 scrollingRGBForEvenSlices = vec3(hsv.z, tempRGB.zx);
    vec3 scrollingRGBForOddSlices = vec3(tempRGB.y, hsv.z, tempRGB.x);
    vec3 scrollingRGB = mix(scrollingRGBForEvenSlices, scrollingRGBForOddSlices, isOddSlice);
    float isNotFirstSlice = clamp(threeSliceSelector, 0.0, 1.0);
    float isNotSecondSlice = clamp(threeSliceSelector - 1.0, 0.0, 1.0);
    return mix(scrollingRGB.xyz, mix(scrollingRGB.zxy, scrollingRGB.yzx, isNotSecondSlice), isNotFirstSlice);
}

float Star(vec2 uvStar, float orthoFlareStrength, float diagFlareStrength) {
    float uvLength = length(uvStar);
    float starBrightness = max(0.0, 0.01 / (uvLength + 0.001));
    starBrightness *= smoothstep(0.9, 0.1, uvLength);
    vec2 uvOrthoFlare = abs(uvStar);
    float orthoFlare = max(0.0, 0.9 - uvOrthoFlare.x * uvOrthoFlare.y * 2048.0);
    orthoFlare *= orthoFlareStrength;
    vec2 uvDiagFlare = abs(ROT45_NoTrig * uvStar);
    float diagFlare = max(0.0, 0.9 - uvDiagFlare.x * uvDiagFlare.y * 4096.0);
    diagFlare *= diagFlareStrength;
    float flares = smax(orthoFlare, diagFlare, 1.2);
    flares *= flares * starBrightness;
    return starBrightness + flares;
}

vec3 DrawStarGrid(vec2 uv) {
    vec3 StarLightContribution = vec3(0.0);
    vec2 uvGrid = fract(uv) - 0.5;
    vec2 idGrid = floor(uv);
    for (int y = -1; y <= 1; y++)
    for (int x = -1; x <= 1; x++) {
        vec2 offset = vec2(float(x), float(y));
        vec2 idOffset = idGrid + offset;
        float rnX = Hash21(idOffset + PI);
        float rnY = Hash21(fract(rnX * 465.321) * idOffset);
        float rnZ = Hash21(fract(rnY * 317.664) * idOffset);
        vec2 randomOffset = vec2(rnX, rnY) - 0.5;
        vec2 uvStar = uvGrid - offset - randomOffset;
        float randomSize = max(0.2, rnX * rnY * rnZ * 3.5);
        vec3 randomColor = normalize(vec3(rnY, rnX, rnZ) + 0.001);
        float randomOrthoFlare = smoothstep(0.9, 1.0, rnY);
        float randomDiagFlare = smoothstep(0.6, 1.0, rnZ);
        StarLightContribution += randomColor * randomSize * Star(uvStar, randomOrthoFlare, randomDiagFlare);
    }
    return StarLightContribution;
}

vec3 DrawStarLayers(vec2 uv, float time, float numberOfLayers) {
    vec3 color = vec3(0.0);
    vec2 parallax = vec2(20.0, 14.0) * sin(time * 0.25 * vec2(0.257, 0.631));
    for (int i = 0; i < 8; i++) {
        float fi = float(i) / numberOfLayers;
        float depth = fract(fi - time);
        float invDepth = 1.0 - depth;
        float starDepthScaling = 1.0 - (invDepth * invDepth);
        float fade = smoothstep(0.0, 0.1, depth) * smoothstep(1.0, 0.5, depth);
        color += DrawStarGrid(uv * starDepthScaling + parallax + fi * 4093.773) * fade;
    }
    return color;
}

void main() {
    vec4 mask = texture(Sampler0, texCoord0.xy);
    if (mask.a < 0.05) { discard; }

    float time = iTime * 0.1;
    vec2 uv = fPos.xy * 6.0;

    vec3 starColor = DrawStarLayers(uv, time, 8.0);
    vec3 nebulaColor = normalize(sin(time * vec3(0.383, 0.653, 0.829) * 2.0 * PI) * 0.5 + 0.5);
    float hue = sin(time * 0.0253 * 2.0 * PI) * 0.499 + 0.5;
    float sat = sin(time * 0.134) * 0.5 + 0.5;
    sat = sat * 0.15 + 0.85;
    float val = sin(time * 0.342) * 0.5 + 0.5;
    val = val * 0.08 + 0.92;
    nebulaColor = HSVtoRGB(vec3(hue, sat, val));
    vec3 luminanceCoefficients = vec3(0.3, 0.59, 0.11);
    vec3 nebulizedStars = dot(starColor, luminanceCoefficients) * nebulaColor;
    vec3 finalStars = mix(nebulizedStars, starColor, 0.57);
    vec3 finalColor = finalStars + nebulaColor * 0.09;

    vec3 shade = vertexColor.rgb * 0.2 + vec3(0.8);
    finalColor *= shade;
    finalColor = clamp(finalColor, 0.0, 1.0);
    fragColor = vec4(finalColor, 0.5) * ColorModulator;
}
