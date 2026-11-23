# Menu de Fears (`d1_000400`)

Établi par lecture du listing `extract/out/exe/d1_000400.s` (image liée à 0x10000).
Portage : `src/main/java/fears/menu/`.

## Enchaînement (`L010092`)

0. Avant le menu, l'exécutable `d1_0d140c` : intro de démarrage (voir plus bas).
1. `L010e7a` : présentation (logo FEARS, texte défilant) jusqu'au clic.
2. `L010238` : fondu d'entrée sur la liste copper 0x29648 (hires, 1 plan) : seul le logo
   « bomb » ondule, sa couleur monte de noir à $4E4040 (incréments $4E/$40/$40 en 8.6,
   64 pas sur 71 VBL).
3. `L0104b0` : le menu, liste copper 0x2a450 (hires 640×256, 5 plans).
4. À la sortie : fondu inverse (`L01027a`, ou `L0102d0` qui coupe aussi la musique), puis
   le choix : START GAME, LOAD, EDIT MAZE, NULL MODEM → `L011742` ; CREDITS → retour en 1.

## L'écran du menu

| Plan | Contenu |
|---|---|
| 1 | logo « bomb » (0x54c80, 640×256), repointé à chaque ligne par la copper |
| 2–4 | texte (0x33260, +0x5000, +0xa000), valeur v → couleur 2v |
| 5 | bande de surbrillance (0x59c80) : 21 lignes pleines sous la ligne pointée → rampe rouge (couleurs 16+) |
| sprite 0 | pointeur 16×17 basse résolution, couleurs 97–99 (BPLCON4 $66) |

**Ondulation** (`L010154`, chaque VBL) : pour chaque ligne écran, la ligne du plan affichée
est l'octet haut d'un compteur 16.16, qui avance de `2·sin[p1] + 2·sin[p2]` d'une ligne à
l'autre (p1 +1°, p2 +2° par ligne ; table `L017b10`, 360 mots). D'une image à l'autre, le
départ descend d'une ligne, p1 avance de 1°, p2 recule de 2°. Le logo (208×197 lores, bits
doublés en hires par `L010406`) est posé ligne 30, pixel 112.

## Pages

Table `L0109dc` : couples (routines, texte), indexés par `L0109d8`. Une page = chaînes
terminées par 0, close par $FF ; 11 lignes de 22 pixels ; chaque chaîne centrée d'après la
table de chasse `L011122` (index `c − 0x20`). Police `L0261e2` : glyphe = mot à
`2c + 120·ligne`, 21 lignes, 3 plans espacés de 0x9d8 (les caractères ≥ 0x3c débordent sur
la ligne suivante de la planche ; c'est voulu).

| Ligne | Page 0 (texte 0x109f4) | Page 1 OPTIONS (0x10a66) |
|---|---|---|
| 3 / 1 | START GAME → 0x10c9c (`L010dee`=1) | WALLS → 0x10cf8 (`L011cb8` bit 1) |
| 4 / 2 | LOAD GAME → 0x10be4 (3) | CEILING → 0x10d24 (bit 0) |
| 5 / 3 | OPTIONS → 0x10cae | DITHERING → 0x10d50 (`L011cba`) |
| 6 / 4 | EDIT MAZE → 0x10c68 (2) | SCREEN SIZE → 0x10cbe (`L011cbe`, MAX BIG LOW MIN) |
| 7 / 5 | NULL MODEM GAME → 0x10bf6 (4) | WALK EFFECT → 0x10d74 (`L011cbc`) |
| 8 / 6 | CREDITS → 0x10c8a (`L010dec`) | DIFFICULTY → 0x10d98 (`L011cc0`, EASY NORMAL HIGH) |
| 7 | | MODEM MAZE → 0x10c08 (`L011cc4`, 1..3) |
| 8 | | HERO → 0x10c36 (`L011cc6`, HUMAN/ALIEN) |
| 10 | | BACK TO MENU → 0x10c7a |

Les routines réécrivent la chaîne en place (« ··ON » / « ·OFF »…) puis retracent la page.
Valeurs initiales : murs et plafond texturés, pas de tramage, MAX, marche ON, NORMAL,
labyrinthe 1, HUMAN. Page 2 (0x10b14) : statistiques de fin de niveau, routines toutes nulles.

## Entrées

- Souris (`L0105b8`) : x 0..0x278 (hires), y 0..0xF7 ; ligne pointée = min(y / 22, 10).
- Joystick (`L0106e8`) : une direction seule accélère de 1 par VBL jusqu'à 10 ; en diagonale
  la seconde de 2 jusqu'à 20 ; changer de direction remet à zéro.
- Bouton gauche ou feu (`L010976`) : routine de la ligne, puis 10 VBL d'attente.
- Musique : module « menu » (`d1_0a3ebc`, M.K. codé en delta), joué dans la VBL pendant la
  présentation et le menu.

## Présentation (`L010e7a`) — `Presentation.java`

Liste copper 0x294d4 : hires 640×256, double champ de jeu (BPLCON0 $f605, BPLCON2 $60 :
champ 2 devant, BPLCON3 PF2OF = 16).

- Champ 1 (plans impairs, 0x72538, 4 plans entrelacés, 320 octets par ligne) : le logo
  FEARS 320×191 lores, bits doublés par `L0113d6`, posé ligne 32 (0x2800 / 320).
- Champ 2 (plans pairs, double tampon 0x33260 / 0x43f70, 3 plans entrelacés) : le texte,
  couleur 16 + v ; la valeur 0 laisse voir le logo.
- Palette : 24 couleurs, accumulateurs 8.6 `L011252`, incréments `L0112e2` (rampe rouge du
  logo, gris du texte). Fondu d'entrée en 64 images (`L011160`, boucle principale).
- Défilement (`L010f3c`, VBL) : une image sur deux (trames longues), le texte remonte d'une
  ligne et la ligne suivante des glyphes de la chaîne en cours est tracée ligne 256, sous
  l'écran. 23 lignes par chaîne (21 de glyphes). Textes à `L011424`, 74 chaînes, en boucle
  après le `$FF` : titre, auteurs, histoire du professeur Bloodheart.
- Clic ou feu (`L0111a8`) : fondu inverse en 64 images, fin quand `L011250` > $46.

## Intro de démarrage (`d1_0d140c`) — `BootIntro.java`

Trois tableaux en hires entrelacé (640×512), sans musique ni entrée. Table `L010162` (copper,
plans, nombre de plans, décalage de trame courte) et table `L0102a2` (24 octets : incréments
d'entrée, de sortie, mots − 1, destinations des couleurs, durée d3).

| Tableau | Image | Pose | Couleurs | d3 |
|---|---|---|---|---|
| 0 | MANYK presents 400×100, 2 plans | x 112, ligne 206 (DIWSTRT $90), coupée ligne 304 | 4 : fond blanc, noir, $B8, $6E | 200 |
| 1 | A game developed by 176×131 | x 224, ligne 182 ($84), coupée 310 | idem | 100 |
| 2 | mascotte BOMB software 416×512, 5 plans | x 128, ligne 0 | 32 | 201 |

Compteur `L010386` par VBL : 1–64 fondu d'entrée (incréments ajoutés aux accumulateurs
`L010538`), d3 à d3 + 63 fondu de sortie, d3 + 64 tableau suivant. Les tableaux 0 et 1
sortent vers le blanc, le 2 vers le noir. Après le troisième, `jsr` vers le menu.

## Entre deux niveaux

**Statistiques** (`L012d48`, page 2 `L010b14`, liste copper 0x29358 : hires, 3 plans, couleur =
valeur du glyphe) — `StatsScreen.java` :

- lignes : `LEVEL nn TIME hh:mm:ss`, `TOTAL TIME`, `ALIENS tués OUT OF total`,
  `SECRETS trouvés OUT OF ???`, `TREASURES pris OUT OF total`, `SCORE` sur 10 chiffres,
  `SAVE GAME`, `CONTINUE GAME` ;
- palette : 8 accumulateurs partant tous de $00F (écran bleu), table `L012f86` retranchée
  64 fois → noir et gris $F0 à $20 ; sortie par la table `L012f56` ;
- le clic n'est lu qu'après le fondu ; pointeur entre $C6 et $D9 (ligne 9) = SAVE GAME
  (`L014444`, pas encore porté), sinon on continue ; pointeur masqué pendant le fondu de sortie ;
- score (`L012fe6`) : `+= 2 × aliens tués + secrets trouvés + 5 × trésors`, cumulé ; secrets =
  déclencheurs jamais actionnés, n° & $3F ≠ 0, mur bas < 11 (MOTEUR.md « monde » §8.2).

**Château** (`L0119bc`, fichier `d1_00d7ec`, 320×512 lores entrelacé, 5 zones animées) —
`CastleScreen.java`. [S] Durée et cadence des animations non relevées : fondu 64 images,
2 s, fondu 64 images, une image d'animation toutes les 8 VBL.

## Sauvegardes (`L014444`, `L014524`) — `SlotScreen.java`, `SaveGame.java`

Écran hires d'un plan (liste copper 0x2b3cc), petite police 8×7 (`L027faa`, un octet par
caractère), une ligne tous les 8 pixels à partir de la ligne 48 : titre (`L0149aa` pour charger,
`L014c3a` pour sauvegarder), ligne vide, 11 emplacements, trois lignes vides, REREAD, CANCEL.
Ligne pointée = (y − $40) / 8 (`L0147be`) : 0–10 emplacements, 14 REREAD, 15 CANCEL.
Emplacement occupé (marque « Fred » à +$2A) en $FFF, vide en $CCC. Le score s'écrit sur 10
chiffres dans une chaîne qui en a 11 : le dernier « 0 » reste.

Enregistrement de 512 octets : temps du niveau, temps total, score ; 9 stats (vies, `L01490e`,
rolling, roquettes, plasma, tronçonneuse, balles, roquettes, cellules) ; niveau suivant ; −1 ;
options ; « Fred » ; difficulté. La santé n'est pas sauvegardée. Le portage écrit un fichier
texte par emplacement dans `~/.fears/sauvegardes/partieNN.sav` (ou `-Dfears.sauvegardes`).
