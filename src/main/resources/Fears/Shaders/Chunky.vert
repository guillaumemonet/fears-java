#import "Common/ShaderLib/GLSLCompat.glsllib"

// Quadrilatère plein cadre du post-traitement (équivalent de Post.vert de jme3-effects).
attribute vec4 inPosition;
attribute vec2 inTexCoord;

varying vec2 texCoord;

void main() {
    vec2 position = inPosition.xy * 2.0 - 1.0;
    gl_Position = vec4(position, 0.0, 1.0);
    texCoord = inTexCoord;
}
