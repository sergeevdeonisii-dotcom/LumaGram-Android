package org.telegram.ui.Components.blur3;

import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;

import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

public class ViewGroupPartRenderer implements IBlur3Capture {

    private final RectF tmpDrawListViewRectF = new RectF();
    private final PointF tmpDrawListViewPointF = new PointF();

    public interface DrawChildMethod {
        boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime);
    }


    private final ViewGroup listView;
    private final DrawChildMethod listViewDrawChildMethod;
    private final ViewGroup listViewParent;

    public ViewGroupPartRenderer(ViewGroup listView, ViewGroup listViewParent, DrawChildMethod listViewDrawChildMethod) {
        this.listView = listView;
        this.listViewDrawChildMethod = listViewDrawChildMethod;
        this.listViewParent = listViewParent;
    }

    public boolean ignoreBlurCap;

    private boolean isVisibleInParent() {
        View current = listView;
        while (true) {
            if (current.getVisibility() != View.VISIBLE || current.getAlpha() <= 0) return false;
            if (current == listViewParent) return true;
            final ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return false;
            current = (View) parent;
        }
    }

    @Override
    public void capture(Canvas canvas, RectF position) {
        // Hidden media pages remain attached; geometry alone does not make them drawable.
        if (!isVisibleInParent()) return;
        final long drawingTime = SystemClock.uptimeMillis();

        if (!ViewPositionWatcher.computeCoordinatesInParent(listView, listViewParent, tmpDrawListViewPointF)) {
            return;
        }

        final float offsetX = tmpDrawListViewPointF.x;
        final float offsetY = tmpDrawListViewPointF.y;
        final int saveCount = canvas.save();
        try {
            canvas.clipRect(position);
            canvas.translate(offsetX, offsetY);

            if (listView instanceof IBlur3Capture && !ignoreBlurCap) {
                IBlur3Capture capture = (IBlur3Capture) listView;
                final float left = position.left, top = position.top;
                final float right = position.right, bottom = position.bottom;
                try {
                    position.offset(-offsetX, -offsetY);
                    capture.capture(canvas, position);
                } finally {
                    position.set(left, top, right, bottom);
                }
            } else {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    final View child = listView.getChildAt(i);
                    if (!ViewPositionWatcher.computeRectInParent(child, listViewParent, tmpDrawListViewRectF)) {
                        continue;
                    }

                    if (!tmpDrawListViewRectF.intersect(position)) {
                        continue;
                    }

                    final int childSaveCount = canvas.save();
                    try {
                        listViewDrawChildMethod.drawChild(canvas, child, drawingTime);
                    } finally {
                        canvas.restoreToCount(childSaveCount);
                    }
                }
            }
        } finally {
            // A captured child may save again: pop our scope, not just its last save.
            canvas.restoreToCount(saveCount);
        }
    }

    @Override
    public void captureCalculateHash(IBlur3Hash builder, RectF position) {
        final boolean visible = isVisibleInParent();
        builder.add(visible);
        if (!visible) return;
        if (!ViewPositionWatcher.computeCoordinatesInParent(listView, listViewParent, tmpDrawListViewPointF)) {
            builder.unsupported();
            return;
        }


        if (listView instanceof IBlur3Capture && !ignoreBlurCap) {
            builder.addF(tmpDrawListViewPointF.x);
            builder.addF(tmpDrawListViewPointF.y);

            IBlur3Capture capture = (IBlur3Capture) listView;

            final float left = position.left, top = position.top;
            final float right = position.right, bottom = position.bottom;
            try {
                position.offset(-tmpDrawListViewPointF.x, -tmpDrawListViewPointF.y);
                capture.captureCalculateHash(builder, position);
            } finally {
                position.set(left, top, right, bottom);
            }
        } else {
            builder.unsupported();
        }
    }
}
