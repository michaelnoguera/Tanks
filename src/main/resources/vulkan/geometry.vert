#version 450
layout(location = 0) in vec4 position;
layout(location = 1) in vec4 color;
layout(location = 2) in vec2 uv;
layout(location = 3) in vec4 shadowClip;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 textureCoordinate;
layout(location = 2) out vec4 shadowClipCoordinate;
void main()
{
    gl_Position = position;
    vertexColor = color;
    textureCoordinate = uv;
    shadowClipCoordinate = shadowClip;
}
