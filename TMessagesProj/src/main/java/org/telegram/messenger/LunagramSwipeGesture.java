package org.telegram.messenger;

import java.util.Arrays;

/** No Android state: one pointer, one immutable action snapshot, at most one release. */
public final class LunagramSwipeGesture {
    public static final int NONE = 0, REPLY = 1, COPY = 2, TRANSLATE = 3, SAVE = 4;
    public static final long MENU_HOLD_MS = 240;
    private int[] actions = new int[0];
    private float originY;
    private int index;
    private int direction = 1;
    private boolean armed;
    private boolean consumed;
    private boolean menuActive;
    private boolean menuPlaced, waitingForPlacementMove;
    private float placementY;
    private long holdSince = -1;
    private float holdX, holdY, lastY, lastRowHeight;
    public void begin(float y, int[] allowedActions) {
        actions = allowedActions == null ? new int[0] : Arrays.copyOf(allowedActions, allowedActions.length);
        originY = y; index = 0; direction = 1; armed = false; consumed = false; menuActive = false; holdSince = -1;
        lastY = y; lastRowHeight = 0; menuPlaced = false; waitingForPlacementMove = false;
    }
    /** Fix the menu's direction once, before arming; bottom-edge menus extend upwards. */
    public void setDirection(int value) { if (!armed && !consumed) direction = value < 0 ? -1 : 1; }
    public int direction() { return direction; }
    /** Position at the end of the dwell, not the old DOWN or reordered action-zero position. */
    public float menuAnchorY() { return armed ? lastY : originY; }
    /** Commit the visible rows once on opening. A clamped menu must use its real row centers. */
    public boolean placeMenu(int value, float firstActionCenterY, float rowHeight) {
        if (!menuActive() || menuPlaced || !Float.isFinite(firstActionCenterY)
                || !Float.isFinite(rowHeight) || rowHeight <= 0) return false;
        direction = value < 0 ? -1 : 1;
        originY = firstActionCenterY; lastRowHeight = rowHeight;
        placementY = lastY; waitingForPlacementMove = true; menuPlaced = true;
        return true;
    }
    public static boolean shouldCapture(float dx, float dy, float slop) {
        return Float.isFinite(dx) && Float.isFinite(dy) && Float.isFinite(slop) && slop > 0
                && -dx >= slop && -dx > Math.abs(dy) * 1.5f;
    }
    public boolean move(float dx, float y, float threshold, float rowHeight) {
        return move(dx, y, threshold, rowHeight, System.nanoTime() / 1000000L);
    }
    public boolean move(float dx, float y, float threshold, float rowHeight, long now) {
        if (consumed || actions.length == 0 || !Float.isFinite(dx) || !Float.isFinite(y)
                || !Float.isFinite(threshold) || !Float.isFinite(rowHeight) || threshold <= 0 || rowHeight <= 0 || now < 0) return false;
        int previous = selected();
        if (-dx >= threshold && !armed) {
            armed = true; holdSince = now; holdX = dx; holdY = y;
        }
        if (-dx < threshold * 0.45f) { armed = false; menuActive = false; holdSince = -1; index = 0; menuPlaced = false; waitingForPlacementMove = false; }
        lastY = y; lastRowHeight = rowHeight;
        if (armed && !menuActive) {
            // A moving/diagonal flick remains Reply. Only a short, stable dwell opens the menu.
            if (Math.abs(dx - holdX) > threshold * .12f || Math.abs(y - holdY) > threshold * .12f) {
                holdSince = now; holdX = dx; holdY = y;
            }
        }
        if (armed && menuActive) {
            // Opening/clamping must not change Reply on a stationary MOVE or an immediate UP.
            // Once the user moves vertically, every visible row is reachable even with Reply
            // in the middle of a custom order and the finger outside the clamped menu.
            if (waitingForPlacementMove && Math.abs(y - placementY) < rowHeight * .12f) return selected() != previous;
            waitingForPlacementMove = false;
            float rowPosition = direction * (y - originY) / rowHeight;
            // A Schmitt boundary avoids alternating actions/haptics around a row edge.
            // Clamp at the ends: the labels never wrap or reorder beneath the finger.
            while (index < actions.length - 1 && rowPosition >= index + .62f) index++;
            while (index > 0 && rowPosition <= index - .62f) index--;
        }
        return selected() != previous;
    }
    /** Called by the overlay frame while held, so a stationary finger needs no new MOVE event. */
    public boolean advanceHold(long now) {
        if (!armed || consumed || menuActive || holdSince < 0 || now < holdSince || now - holdSince < MENU_HOLD_MS) return false;
        menuActive = true; index = 0;
        menuPlaced = false; waitingForPlacementMove = false;
        for (int i = 0; i < actions.length; i++) if (actions[i] == REPLY) { index = i; break; }
        originY = lastY - direction * index * lastRowHeight;
        return true;
    }
    public boolean menuActive() { return menuActive && armed && !consumed; }
    public boolean waitingForMenu() { return armed && !consumed && !menuActive; }
    public int selected() {
        if (!armed || consumed || actions.length == 0) return NONE;
        if (menuActive) return actions[index];
        for (int action : actions) if (action == REPLY) return REPLY;
        return NONE;
    }
    public int release(boolean canceled) {
        int result = canceled ? NONE : selected(); consumed = true; armed = false; menuActive = false; holdSince = -1; return result;
    }
    public void cancel() { release(true); }
    public int[] actions() { return actions; }
    public int selectedIndex() { return index; }
    public float originY() { return originY; }
    public static boolean validOrder(int[] values) {
        if (values == null || values.length != 4) return false;
        boolean[] seen = new boolean[5];
        for (int value : values) { if (value < 1 || value > 4 || seen[value]) return false; seen[value] = true; }
        return true;
    }
    public static int[] decodeOrder(String value) {
        if (value != null && value.length() <= 16) {
            try {
                String[] tokens = value.split(",", -1); int[] result = new int[tokens.length];
                for (int i = 0; i < tokens.length; i++) result[i] = Integer.parseInt(tokens[i]);
                if (validOrder(result)) return result;
            } catch (NumberFormatException ignored) { }
        }
        return new int[]{REPLY, COPY, TRANSLATE, SAVE};
    }
}
