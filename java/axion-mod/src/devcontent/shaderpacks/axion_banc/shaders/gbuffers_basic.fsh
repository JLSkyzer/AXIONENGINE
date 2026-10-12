#version 120

// Le bleu nul du pack, comme gbuffers_textured.

uniform sampler2D lightmap;

varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = glcolor * texture2D(lightmap, lmcoord);
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(color.rg, 0.0, color.a);
}
