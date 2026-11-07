#version 450

layout(location = 0) in vec4 velocity;
layout(location = 1) in vec4 color;
layout(location = 2) in vec2 offsets;
layout(location = 3) in vec4 particleData;

layout(push_constant) uniform FireworkPushConstants
{
    layout(offset = 16) mat4 transform;
    vec4 gravityTime;
    vec4 tintGlow;
} pc;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 textureCoordinate;
layout(location = 2) out vec4 shadowClipCoordinate;

void main()
{
    float time = pc.gravityTime.w;
    vec3 gravity = pc.gravityTime.xyz;
    vec3 tint = pc.tintGlow.xyz;
    float glow = pc.tintGlow.w;
    float maxAge = particleData.x;
    vec3 particlePosition;
    vec4 resultColor;

    if (glow >= 0.0)
    {
        float fraction = max(0.0, maxAge - time * (1.0 - glow));
        float scale = (1.0 + glow * 7.0) * fraction / 80.0;
        particlePosition = velocity.xyz * time + 0.5 * gravity * time * time
                + vec3(offsets, 0.0) * scale;
        float alpha = maxAge == 0.0 ? 1.0 : 1.0 - time / maxAge;
        resultColor = vec4((tint + color.rgb) * (1.0 - glow * color.a), alpha);
    }
    else
    {
        float start = offsets.x;
        float depthOffset = offsets.y;
        float t = max(time - start, 0.0);
        float fraction = max(0.0, maxAge - time) * max((20.0 - start) / 20.0, 0.0);
        vec3 v = velocity.xyz + gravity * t;
        float lengthV = length(v);
        float crossScale = lengthV == 0.0 ? 0.0 : depthOffset / lengthV;
        particlePosition = velocity.xyz * t + 0.5 * gravity * t * t
                + vec3(v.y, -v.x, 0.0) * crossScale * fraction / 80.0;
        float factor = maxAge == 0.0 ? 0.0
                : 1.0 - (time / maxAge) * max((60.0 - start) / 60.0, 0.0);
        resultColor = vec4((tint + color.rgb * color.a) * factor, 1.0);
    }

    gl_Position = pc.transform * vec4(particlePosition, 1.0);
    vertexColor = clamp(resultColor, 0.0, 1.0);
    textureCoordinate = vec2(0.0);
    shadowClipCoordinate = vec4(0.0);
}
