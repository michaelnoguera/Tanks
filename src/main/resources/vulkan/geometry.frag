#version 450
layout(set = 0, binding = 0) uniform sampler2D image;
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 textureCoordinate;
layout(location = 0) out vec4 outputColor;
void main()
{
    outputColor = vertexColor * texture(image, textureCoordinate);
    if (outputColor.a == 0.0) discard;
}
