#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;

attribute vec3 inPosition;
attribute vec2 inTexCoord;

varying vec2 texCoord;
varying vec3 worldPosition;

void main() {
    texCoord = inTexCoord;
    worldPosition = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
