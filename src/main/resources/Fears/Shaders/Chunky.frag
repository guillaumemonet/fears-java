#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_Texture;
uniform vec2 m_ViewSize;
uniform float m_Lores;
uniform float m_Line;

varying vec2 texCoord;

void main() {
    // Position en pixels basse résolution (x) et en lignes depuis le haut (y).
    vec2 pixel = texCoord * m_ViewSize;
    float lx = pixel.x / m_Lores;
    float ly = (m_ViewSize.y - pixel.y) / m_Line;
    float row = floor(ly / 2.0);
    float shift = 0.0;
#ifdef DITHER
    // Seconde ligne de la rangée : le motif des plans est décalé d'un pixel.
    if (mod(floor(ly), 2.0) >= 1.0) {
        shift = 1.0;
    }
#endif
    float column = max(0.0, floor((lx - shift) / 3.0));
    // Chaque pixel chunky prend la couleur du centre de sa case (3 × 2).
    vec2 center = vec2((column * 3.0 + 1.5) * m_Lores, m_ViewSize.y - (row * 2.0 + 1.0) * m_Line);
    gl_FragColor = texture2D(m_Texture, center / m_ViewSize);
}
