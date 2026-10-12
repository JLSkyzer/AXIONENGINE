#version 120

// Eclaire par la lightmap, puis ecrit un bleu nul : le banc reconnait ainsi, a la capture, un pixel
// sorti des programmes du pack (T-480).

uniform sampler2D texture;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(texture, texcoord) * glcolor;
    color *= texture2D(lightmap, lmcoord);
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(color.rg, 0.0, color.a);
}
