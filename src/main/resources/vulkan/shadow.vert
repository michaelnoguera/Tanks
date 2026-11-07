#version 450
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 textureCoordinate;
layout(location = 3) in vec4 lightClipPosition;

layout(location = 0) out float alpha;
layout(location = 1) out vec2 uv;

void main()
{
    // Vulkan clip space keeps depth in the range [0, w]. A zero w cannot be
    // perspective-divided, so place that vertex beyond the far clip plane.
    gl_Position = lightClipPosition.w == 0.0
        ? vec4(0.0, 0.0, 2.0, 1.0)
        : lightClipPosition;
    alpha = vertexColor.a;
    uv = textureCoordinate;
}
