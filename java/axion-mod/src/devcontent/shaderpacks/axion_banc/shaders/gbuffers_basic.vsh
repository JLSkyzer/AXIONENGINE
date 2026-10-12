#version 120

// Shaderpack de test du banc de rendu (T-480) : la geometrie sans texture, ciel compris.

varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    gl_Position = ftransform();
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
}
