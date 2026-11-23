# Moteur 3D du portage

Le moteur d'origine (`d1_0282da`, spécifié dans `../docs/MOTEUR.md`) est un lanceur de rayons
sur liste copper. Le portage garde **sa logique** (déplacements, collisions, hauteurs, portes,
ramassage, règles de visibilité des faces) et remplace **son rendu** par une vraie géométrie 3D
dans jMonkeyEngine.

## Ce qui est porté tel quel (`fears.engine`)

- `EngineData` : tables lues dans l'exécutable — sinus (0x182D4), objets 1 à 14 (0x11FFA :
  image, W, Hs, dy), sons (0x1F8A0 : échantillon, période, volume, durée).
- `Trig` : sinus **tronqués** (pas arrondis), interpolés à 1080 entrées, 0 remplacé par 1.
- `Player` : position en case + 16.16 locale, pas de 7·sin/cos Q15, course ×2, rotation 6 crans,
  collisions `L013ef4`/`L014054`/`L013dcc` (rayon 23, marche 1 unité, ouverture 6, objets > 12,
  déclencheur d'une porte en mouvement), gravité et montée amortie `L013706`, balancement.
- `World` : grille A vivante, A d'origine, B, grille C (objets, déclencheurs).
- `Doors` : activation à l'Espace (`L014212` : quadrant, case visée, masque de sens, « sur la
  case »), mouvement d'une unité par trame (une VBL sur deux) vers B puis vers A, une seule porte
  à la fois, bascule des interrupteurs (texture > 13), sortie n° 64 → niveau suivant.
- `Stats` : ramassage en entrant dans la case (`L013866`), quantités et maxima, son « plein »,
  bouclier 900 VBL, sol acide (texture de sol 0) −1 toutes les 10 VBL.

- `Weapons` : six armes (poing, pistolet, rolling, roquettes, plasma, tronçonneuse), délais,
  munitions, images de tir ou recul, changement automatique quand la munition manque,
  changement d'arme abaissé puis relevé (VSTART $D3 → $121 → $D0).
- `Hud` : comme l'original, les chiffres, noms d'armes et messages de combinaison sont écrits
  **dans une copie des données des sprites** (0x20410, 0x20AD0, 0x21190) aux adresses du moteur,
  avec ses polices, puis décodés : AMMO = sprite 6 (couleurs 124–127), HEALTH/LIVES = sprites 4+5
  attachés (couleurs 112–127, santé en couleur 116, rouge sous 32 points). Position :
  x = HSTART − $80, y = VSTART − $2A.

- `Aliens` : types lus dans l'exécutable (0x15780 : PV, cadence, projectile, images de marche,
  tir, douleur, mort, sons, tailles par état) ; apparition depuis les objets 15–17 ; activation
  dans le demi-carré 13 × 6 devant le joueur (sans ligne de vue) ; marche d'1 unité par VBL,
  retenue au centre de la case si la voisine est bloquée (dénivelé > 1, plafond, autre alien,
  joueur) ; tir toutes les 60 VBL à moins de 6 cases, projectiles à 8 unités/VBL perdant 1 point
  par case ; le joueur perd la moitié des dégâts restants (son « oopf », éclair rouge 16 crans) ;
  facile = 10/20/30, difficile = +3 PV.
- Tirs du joueur : pistolet et rolling instantanés sur l'alien le plus proche couvrant le centre
  de la vue et non masqué ; poing et tronçonneuse au contact ; roquette et plasma en projectile
  (16 unités/VBL, 8 points, un seul à la fois). Mort de l'alien quand ses PV deviennent négatifs.

- `Boss` (niveau 30) : la texture de mur 11, 500 PV ; table de remappage des murs animée toutes
  les 20 trames (`L01148a`) ; salves de 7 projectiles depuis (15,25) (`L011582`) ; −1 par tir de
  pistolet/rolling quand la visée montre la texture 11, −10 par roquette arrivée sur sa case ;
  portes 6, 7, 8 forcées sous 400/300/200 PV ; à sa mort, mur bas de (14,25) à 12 et porte 10.

## Cadence

Toute la logique tourne à **50 Hz fixes** (une VBL PAL), quelle que soit la fréquence de
l'écran : un accumulateur de temps exécute autant de VBL que le temps écoulé en contient. Ce que
l'original fait « par trame » à 25 images/s (pas des portes, défilement de la texture 0) tourne
une VBL sur deux. [à faire] Interpoler l'affichage entre deux VBL pour les écrans rapides.

## Ce qui est réinterprété en 3D

| Point | Choix | Statut |
|---|---|---|
| Échelle verticale | 1 unité de hauteur = 0,16 case : texels carrés (64 texels = 1 case = 6,25 unités, §4.2) | [C] déduit |
| Faces | `LevelMesh` : murs bas/haut d'après §3.2, textures de la case vue, u/v d'après §3.3–3.4 ; maillage refait quand une porte bouge | [C] |
| Ombrage | 4 paliers, `(d − 7500) >> 11` murs, `(d − 9000) >> 11` sols, −2/15 par palier ; noir au-delà de 14300 | [C] formules |
| Objets | panneaux tournés vers le joueur ; hauteur = Hs unités, largeur = W × 0,186 case, centre à `réf − 2,7465 + dy/16` (réf = plafond pour la stalactite) ; 3 crans plus sombres au-delà de 9500 | [C] formules, largeur [S] |
| Unité de distance | 1590 unités de rayon par case, estimé d'après les focales | [S] à vérifier |
| Champ de vision | 50° vertical par défaut (`-Dfears.fov`) ; l'original fait ≈ 30,7° × 28,5° | choix |
| Texture 0 | défile de 25 texels/s (1 par image à 25 images/s) | [C] |
| Aliens | panneaux tournés vers le joueur (une seule vue dans l'original) ; hauteur Hs, largeur W × 0,186 case, centre à `sol − 2,7465 + yoff` (yoff en unités : les pieds touchent le sol pour tous les types) | [C] formules, [S] unité de yoff |
| Ligne de tir | test de cases pleines ou trop basses le long de la visée, au lieu de la découpe par colonne | adaptation |
| Cadrage | la vue 3D et le HUD occupent le même rectangle 320×256 (5:4), centré dans la fenêtre | [C] écran d'origine |
| Sons | échantillons lus dans l'exécutable, joués à `3 546 895 / période` Hz, volume/64 | [C] |

## Pas encore porté

Alien translucide (type 11) (bascule de
la banque de couleurs des sprites, `L0134e4`), sons d'ambiance des murs, carte, message de vies et fondu rouge à la mort, séquence de fin après le niveau 30.

## Lancer

- `gradle run` : intro, présentation, menu ; START GAME ouvre le niveau 1.
- `gradle run -Dfears.niveau=N` : niveau N directement. `-Dfears.intro=off` : sans l'intro.
- En jeu : flèches ou ZQSD/WASD, Maj pour courir, souris pour tourner, bouton droit pour
  avancer, clic gauche ou Ctrl pour tirer, 1 à 6 pour l'arme, Espace pour les portes et
  interrupteurs, Échap pour revenir au menu. (Sur Amiga : Entrée pour courir, Maj pour tirer.)
- Captures automatiques : `-Dfears.capture=f.png -Dfears.capture.delay=6 -Dfears.capture.quit=true`,
  avec un script d'entrées `-Dfears.script=avancer:120,espace:1,attendre:30,droite:20`
  (avancer, reculer, gauche, droite, espace, courir, tirer, arme1..arme6, attendre ; durées en VBL).
