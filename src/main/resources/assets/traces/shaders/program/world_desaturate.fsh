#version 150

uniform sampler2D DiffuseSampler;
uniform float Desaturation;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 source = texture(DiffuseSampler, texCoord);
    float luminance = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 gray = vec3(luminance);
    fragColor = vec4(mix(source.rgb, gray, Desaturation), source.a);
}
