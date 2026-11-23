#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_ColorMap;
uniform float m_DistancePerCell;
uniform float m_Range;
uniform float m_ShadeFrom;
uniform vec3 g_CameraPosition;

varying vec2 texCoord;
varying vec3 worldPosition;

void main() {
    vec4 color = texture2D(m_ColorMap, texCoord);
    if (color.a < 0.5) {
        discard;
    }
    float distance = length(worldPosition.xz - g_CameraPosition.xz) * m_DistancePerCell;
    if (distance > m_Range) {
        discard;
    }
#ifdef TRANSLUCENT
    // L0120DA : fond |= (fond + 4) & $F sur le quartet bleu — approché par un ajout de bleu
    // (mélange additif réglé côté matériau).
    gl_FragColor = vec4(0.0, 0.0, 4.0 / 15.0, 1.0);
    return;
#endif
    // Deux niveaux seulement : normal, ou assombri de 3 crans de 1/15.
    float shade = distance >= m_ShadeFrom ? 3.0 / 15.0 : 0.0;
    gl_FragColor = vec4(max(color.rgb - shade, 0.0), 1.0);
}
