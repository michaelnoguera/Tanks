#version 450

layout(set = 0, binding = 0) uniform sampler2D albedoImage;

layout(location = 0) in float vertexAlpha;
layout(location = 1) in vec2 textureCoordinate;

void main()
{
    if (vertexAlpha == 0.0 || texture(albedoImage, textureCoordinate).a == 0.0)
        discard;
}
