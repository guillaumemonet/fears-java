package fears.engine;

/**
 * Le bilan d'un niveau ({@code L015558}, comptes au chargement puis à la sortie).
 *
 * @param level  n° du niveau, de 1 à 30
 * @param time   durée en VBL ({@code L015ed8})
 */
public record LevelResult(int level, int time, int aliensTotal, int aliensKilled,
                          int secretsTotal, int secretsFound, int goldTotal, int goldTaken) {
}
