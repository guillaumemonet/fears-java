#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_ColorMap;
uniform float m_ShadeStart;
uniform float m_ShadeStep;
uniform float m_ShadeAmount;
uniform float m_DistancePerCell;
uniform float m_Range;
uniform vec2 m_Scroll;
uniform vec3 g_CameraPosition;
uniform float g_Time;

varying vec2 texCoord;
varying vec3 worldPosition;

void main() {
    // Distance horizontale, comme celle des rayons (pas de correction fish-eye).
    float distance = length(worldPosition.xz - g_CameraPosition.xz) * m_DistancePerCell;
    if (distance > m_Range) {
        discard;
    }
    // Palier n = (d - début) >> 11 ; chaque palier retire 2 crans de 1/15 par composante.
    float level = max(0.0, floor((distance - m_ShadeStart) / m_ShadeStep));
    vec4 color = texture2D(m_ColorMap, texCoord + m_Scroll * g_Time);
    gl_FragColor = vec4(max(color.rgb - level * m_ShadeAmount, 0.0), 1.0);
}
