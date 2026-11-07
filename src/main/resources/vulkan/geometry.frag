#version 450
layout(set = 0, binding = 0) uniform sampler2D image;
layout(set = 1, binding = 0) uniform sampler2D shadowMap;
layout(constant_id = 0) const int mode = 0;
layout(push_constant) uniform ShadowSettings
{
    float shadowBrightness;
} shadowSettings;
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 textureCoordinate;
layout(location = 2) in vec4 shadowClipCoordinate;
layout(location = 0) out vec4 outputColor;

float shadowVisibility(vec4 clip, vec2 uvDx, vec2 uvDy, float depthDx, float depthDy)
{
    if (clip.w <= 0.0)
        return 1.0;

    vec3 projected = clip.xyz / clip.w;
    vec2 uv = projected.xy * 0.5 + 0.5;
    if (projected.z < 0.0 || projected.z > 1.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))))
        return 1.0;

    ivec2 dimensions = textureSize(shadowMap, 0);
    vec2 texel = 1.0 / vec2(dimensions);
    vec2 gradient = vec2(0.0);
    float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
    if (abs(determinant) > 1.0e-10)
    {
        gradient.x = (depthDx * uvDy.y - uvDx.y * depthDy) / determinant;
        gradient.y = (uvDx.x * depthDy - depthDx * uvDy.x) / determinant;
        // Preserve ordinary wall slopes; the per-tap correction below bounds grazing cases.
        gradient = clamp(gradient, vec2(-8.0), vec2(8.0));
    }

    vec2 texelPosition = uv * vec2(dimensions) - 0.5;
    ivec2 base = ivec2(floor(texelPosition));
    vec2 fraction = fract(texelPosition);
    float visible = 0.0;
    float totalWeight = 0.0;
    const float bias = 0.00003;
    for (int y = 0; y <= 1; y++)
    {
        for (int x = 0; x <= 1; x++)
        {
            ivec2 sampleTexel = base + ivec2(x, y);
            if (any(lessThan(sampleTexel, ivec2(0))) || any(greaterThanEqual(sampleTexel, dimensions)))
                continue;

            vec2 weightAxis = mix(vec2(1.0) - fraction, fraction, vec2(x, y));
            float weight = weightAxis.x * weightAxis.y;
            vec2 sampleUv = (vec2(sampleTexel) + 0.5) * texel;
            float depthCorrection = clamp(dot(gradient, sampleUv - uv), -0.02, 0.02);
            float receiverDepth = projected.z + depthCorrection;
            float storedDepth = texelFetch(shadowMap, sampleTexel, 0).r;
            visible += (receiverDepth - bias <= storedDepth ? 1.0 : 0.0) * weight;
            totalWeight += weight;
        }
    }

    return totalWeight > 0.0 ? mix(shadowSettings.shadowBrightness, 1.0, visible / totalWeight) : 1.0;
}

void main()
{
    float safeW = shadowClipCoordinate.w == 0.0 ? 1.0 : shadowClipCoordinate.w;
    vec3 projected = shadowClipCoordinate.xyz / safeW;
    vec2 shadowUv = projected.xy * 0.5 + 0.5;
    vec2 shadowUvDx = dFdx(shadowUv);
    vec2 shadowUvDy = dFdy(shadowUv);
    float shadowDepthDx = dFdx(projected.z);
    float shadowDepthDy = dFdy(projected.z);

    vec4 color = vertexColor * texture(image, textureCoordinate);
    if (color.a == 0.0)
        discard;

    if (mode == 0)
    {
        color.rgb *= shadowVisibility(shadowClipCoordinate, shadowUvDx, shadowUvDy, shadowDepthDx, shadowDepthDy);
        outputColor = color;
    }
    else
    {
        vec4 premultiplied = vec4(color.rgb * color.a, color.a);
        outputColor = premultiplied * premultiplied;
    }
}
