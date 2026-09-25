#version 300 es
// OpenGL ES 3.0 port of SameBoy's Shaders/MasterShader.fsh (Expat license, see licenses/SameBoy.txt).
// The filter files next to this one are SameBoy's own; each defines
//   vec4 scale(sampler2D image, vec2 position, vec2 input_resolution, vec2 output_resolution)
// and is pasted in below, at the filter placeholder.
precision highp float;
precision highp int;
// Samplers default to lowp in fragment shaders; filters compare colours, so keep full precision.
precision highp sampler2D;

uniform sampler2D image;
uniform sampler2D previous_image;
uniform int frame_blending_mode;

uniform vec2 output_resolution;
uniform vec2 origin;

#define equal(x, y) ((x) == (y))
#define inequal(x, y) ((x) != (y))
#define STATIC
#define GAMMA (2.2)

out vec4 frag_color;

vec4 _texture(sampler2D t, vec2 pos)
{
    return pow(texture(t, pos), vec4(GAMMA));
}

vec4 texture_relative(sampler2D t, vec2 pos, vec2 offset)
{
    vec2 input_resolution = vec2(textureSize(t, 0));
    return _texture(t, (floor(pos * input_resolution) + offset + vec2(0.5, 0.5)) / input_resolution);
}

#define texture _texture

#line 1
{filter}

#define BLEND_BIAS (1.0 / 3.0)

#define DISABLED 0
#define SIMPLE 1
#define ACCURATE_EVEN 2
#define ACCURATE_ODD 3

void main()
{
    vec2 position = gl_FragCoord.xy - origin;
    position /= output_resolution;
    position.y = 1.0 - position.y;
    vec2 input_resolution = vec2(textureSize(image, 0));

    float ratio;
    if (frame_blending_mode == SIMPLE) {
        ratio = 0.5;
    }
    else if (frame_blending_mode == ACCURATE_EVEN || frame_blending_mode == ACCURATE_ODD) {
        bool even_line = (int(position.y * input_resolution.y) & 1) == 0;
        if (frame_blending_mode == ACCURATE_ODD) even_line = !even_line;
        ratio = even_line ? BLEND_BIAS : 1.0 - BLEND_BIAS;
    }
    else {
        frag_color = pow(scale(image, position, input_resolution, output_resolution), vec4(1.0 / GAMMA));
        return;
    }

    frag_color = pow(mix(scale(image, position, input_resolution, output_resolution),
                         scale(previous_image, position, input_resolution, output_resolution), ratio), vec4(1.0 / GAMMA));
}
