#version 120

// Shaderpack de test du banc de rendu (T-480) : texture, couleur de sommet et lightmap, rien de plus.
// Iris retombe sur ce programme pour le terrain, les entites et tout ce qui est texture.

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
}
