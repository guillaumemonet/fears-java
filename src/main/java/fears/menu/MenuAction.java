package fears.menu;

/** Ce que le joueur a choisi en quittant le menu (variable {@code L010dee}, et {@code L010dec}). */
public enum MenuAction {
    /** START GAME ({@code L010dee} = 1). */
    START_GAME,
    /** EDIT MAZE (2). */
    EDIT_MAZE,
    /** LOAD GAME (3). */
    LOAD_GAME,
    /** NULL MODEM GAME (4). */
    NULL_MODEM,
    /** CREDITS ({@code L010dec} = 1) : la séquence de présentation est rejouée. */
    CREDITS
}
