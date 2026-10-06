package org.telegram.ui.Components.chat;

import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatMessageCell;

public final class ChatActivityDraftMessageMeasureControllerTest {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static MessageObject message(int id, long group, boolean pending, boolean out) {
        return new MessageObject(id, group, pending, out);
    }
    public static void main(String[] args) {
        ChatActivityDraftMessageMeasureController controller = new ChatActivityDraftMessageMeasureController();
        RecyclerView list = new RecyclerView();
        list.height = 640;
        list.paddingTop = 20;
        list.paddingBottom = 20;
        controller.setRecyclerView(list);

        check(!controller.filter(message(0, 0, true, false)), "zero sentinel cannot select an unassigned message");
        check(controller.getOverrideMeasureHeight(message(23, 0, false, false), 90) == 90, "ordinary messages retain their content height");

        controller.setMessageIdToOverride(-7, 0);
        MessageObject draft = message(-7, 0, true, false);
        check(controller.getOverrideMeasureHeight(draft, 160) == 160, "active streaming draft never fills the viewport");
        check(!controller.hasAdditionalHeight(), "active draft has no artificial space below its bubble");
        check(controller.getOverrideMeasureHeight(draft, 80) == 80, "shrinking live draft follows its actual content height");

        check(controller.onMessageIdChanged(-7, 42, 0), "draft rebound to final server id");
        check(controller.getOverrideMeasureHeight(message(42, 0, false, false), 90) == 90, "short final response releases all extra height");
        check(!controller.hasAdditionalHeight(), "final response clears padding state");
        check(!controller.filter(message(42, 0, false, false)), "final response retires the override");

        controller.setMessageIdToOverride(-8, 777);
        controller.getOverrideMeasureHeight(message(-8, 777, true, false), 100);
        check(controller.getOverrideMeasureHeight(message(43, 777, false, false), 110) == 110, "final group sibling returns to natural height");
        check(!controller.hasAdditionalHeight(), "group completion releases padding");

        controller.setMessageIdToOverride(44, 0);
        check(controller.getOverrideMeasureHeight(message(44, 0, false, false), 750) == 750, "long final response is never truncated");
        check(!controller.filter(message(44, 0, false, false)), "long final response also retires override");

        controller.setMessageIdToOverride(-9, 0);
        check(controller.getOverrideMeasureHeight(message(-9, 0, false, true), 90) == 90, "outgoing pending message cannot push itself to the top");
        check(controller.getOverrideMeasureHeight(message(99, 0, false, false), 40) == 40, "other messages are not expanded");
        check(!controller.hasAdditionalHeight(), "there is no reservation for an unrelated row to inherit");
        list.height = 360;
        check(controller.getOverrideMeasureHeight(message(-9, 0, false, true), 90) == 90, "opening the keyboard cannot inflate an outgoing message");
        check(controller.onMessageIdChanged(-9, 46, 0), "outgoing message rebound to acknowledged server id");
        check(controller.getOverrideMeasureHeight(message(46, 0, false, true), 90) == 90, "server acknowledgment cannot collapse a viewport-sized row and bounce the chat");

        controller.setMessageIdToOverride(-10, 0);
        MessageObject thinking = message(-10, 0, true, false);
        for (int viewport : new int[] {360, 640, 980}) {
            list.height = viewport;
            list.paddingBottom = viewport == 360 ? 80 : 20;
            check(controller.getOverrideMeasureHeight(thinking, 48) == 48, "thinking placeholder remains compact across keyboard/reply keyboard sizes");
            check(!controller.hasAdditionalHeight(), "typing state cannot suppress the normal page-down button");
        }
        check(controller.getOverrideMeasureHeight(thinking, 1200) == 1200, "long live response is not capped by viewport size");

        controller.setMessageIdToOverride(45, 0);
        ChatMessageCell visible = new ChatMessageCell(message(45, 0, true, false));
        list.children.add(visible);
        controller.onRequestLayout();
        check(visible.forcedLayouts == 1, "viewport changes invalidate active row measure");
        controller.onScroll();
        check(controller.filter(visible), "visible active response is retained");
        list.children.clear();
        controller.onScroll();
        check(!controller.filter(visible), "scrolling away releases override");

        ChatActivityDraftMessageMeasureController detached = new ChatActivityDraftMessageMeasureController();
        detached.setMessageIdToOverride(-11, 0);
        check(detached.getOverrideMeasureHeight(message(-11, 0, true, false), 80) == 80, "measurement before recycler attachment is safe");
        detached.onRequestLayout();
        detached.onScroll();
        System.out.println("Bot draft/final height regressions passed (production controller; platform fixtures, not rendered UI).");
    }
}
