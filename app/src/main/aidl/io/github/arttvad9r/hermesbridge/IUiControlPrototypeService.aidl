package io.github.arttvad9r.hermesbridge;

import android.os.Bundle;

/**
 * Experimental UI-control surface kept separate from the package-management UserService.
 *
 * This interface is intentionally tiny and typed. It has no executable, shell string,
 * argv, key-event, text-input, display-selection, or arbitrary-command parameter.
 */
interface IUiControlPrototypeService {
    void destroy() = 16777114;

    Bundle tapPrimaryDisplay(int x, int y) = 1;
    Bundle swipePrimaryDisplay(
        int startX,
        int startY,
        int endX,
        int endY,
        int durationMillis
    ) = 2;
}
